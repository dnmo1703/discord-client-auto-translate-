use crate::direct;
use base64::{Engine, engine::general_purpose::STANDARD};
use rustls::{ClientConfig, RootCertStore, pki_types::ServerName};
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, LazyLock};
use std::time::Duration;
use tokio::io::{self as aio, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{RwLock, Semaphore};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_HEADER: usize = 8192;
const MAX_CONNECTIONS: usize = 32;
const DISCORD_DOMAINS: &[&str] = &[
    "discord.com",
    "discordapp.com",
    "discordapp.net",
    "discord.gg",
    "discord.media",
    "discordcdn.com",
];

#[derive(Clone)]
pub(crate) enum Protocol {
    Socks5,
    Http,
    Https,
}

impl Protocol {
    pub(crate) fn from_name(name: &str) -> io::Result<Self> {
        match name {
            "SOCKS5" => Ok(Self::Socks5),
            "HTTP" => Ok(Self::Http),
            "HTTPS" => Ok(Self::Https),
            _ => Err(invalid("Unknown proxy protocol")),
        }
    }
}

#[derive(Clone)]
pub(crate) struct Relay {
    pub(crate) protocol: Protocol,
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) username: String,
    pub(crate) password: String,
}

#[derive(Clone)]
pub(crate) struct Route {
    pub(crate) enabled: bool,
    pub(crate) relay: Option<Relay>,
}

trait Tunnel: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Tunnel for T {}
type Transport = Box<dyn Tunnel>;

static TLS: LazyLock<TlsConnector> = LazyLock::new(|| {
    let roots = RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    TlsConnector::from(Arc::new(config))
});

pub(crate) async fn listen(
    route: Arc<RwLock<Route>>,
) -> io::Result<(u16, tokio::task::JoinHandle<()>)> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let address = listener.local_addr()?;
    let limit = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    let task = tokio::spawn(async move {
        while let Ok((client, _)) = listener.accept().await {
            let Ok(permit) = Arc::clone(&limit).try_acquire_owned() else {
                continue;
            };
            let route = Arc::clone(&route);
            tokio::spawn(async move {
                let _permit = permit;
                let _ = handle(client, route, address).await;
            });
        }
    });
    Ok((address.port(), task))
}

async fn handle(
    mut client: TcpStream,
    route: Arc<RwLock<Route>>,
    listener: SocketAddr,
) -> io::Result<()> {
    let selected = route.read().await.clone();
    if !selected.enabled {
        return response(&mut client, "503 Service Unavailable").await;
    }

    let request = timeout(CONNECT_TIMEOUT, read_header(&mut client)).await??;
    let Some(host) = connect_host(&request) else {
        return response(&mut client, "403 Forbidden").await;
    };

    let mut upstream = match timeout(
        CONNECT_TIMEOUT,
        connect(selected.relay.as_ref(), &host, Some(listener)),
    )
    .await
    {
        Ok(Ok(upstream)) => upstream,
        _ => return response(&mut client, "502 Bad Gateway").await,
    };
    client
        .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        .await?;
    if selected.relay.is_none() {
        timeout(
            CONNECT_TIMEOUT,
            direct::forward_hello(&mut client, &mut upstream, &host),
        )
        .await??;
    }
    aio::copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

fn connect_host(header: &str) -> Option<String> {
    let first = header.split("\r\n").next()?;
    let mut parts = first.split(' ');
    if parts.next()? != "CONNECT" {
        return None;
    }
    let host = parts.next()?.strip_suffix(":443")?.to_ascii_lowercase();
    if !matches!(parts.next()?, "HTTP/1.0" | "HTTP/1.1") || parts.next().is_some() {
        return None;
    }
    let allowed = host.len() <= 253
        && host
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'-')
        && DISCORD_DOMAINS.iter().any(|domain| {
            host == *domain
                || host
                    .strip_suffix(domain)
                    .is_some_and(|prefix| prefix.ends_with('.'))
        });
    allowed.then_some(host)
}

async fn connect(
    relay: Option<&Relay>,
    host: &str,
    listener: Option<SocketAddr>,
) -> io::Result<Transport> {
    let Some(relay) = relay else {
        return direct::connect(host)
            .await
            .map(|socket| Box::new(socket) as Transport);
    };

    let socket = TcpStream::connect((relay.host.as_str(), relay.port)).await?;
    if Some(socket.peer_addr()?) == listener {
        return Err(invalid("Proxy points at its own listener"));
    }
    let mut stream: Transport = match relay.protocol {
        Protocol::Https => Box::new(open_tls(socket, &relay.host).await?),
        _ => Box::new(socket),
    };
    match relay.protocol {
        Protocol::Socks5 => socks_connect(&mut stream, relay, host).await?,
        Protocol::Http | Protocol::Https => http_connect(&mut stream, relay, host).await?,
    }
    Ok(stream)
}

async fn http_connect(stream: &mut Transport, relay: &Relay, host: &str) -> io::Result<()> {
    let target = format!("{host}:443");
    let mut request = format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n");
    if !relay.username.is_empty() {
        let credentials = STANDARD.encode(format!("{}:{}", relay.username, relay.password));
        request.push_str(&format!("Proxy-Authorization: Basic {credentials}\r\n"));
    }
    request.push_str("\r\n");
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;
    let reply = read_header(stream).await?;
    let mut status = reply.split("\r\n").next().unwrap_or("").split(' ');
    if !matches!(status.next(), Some("HTTP/1.1" | "HTTP/1.0")) || status.next() != Some("200") {
        return Err(invalid("HTTP proxy rejected CONNECT"));
    }
    Ok(())
}

async fn socks_connect(stream: &mut Transport, relay: &Relay, host: &str) -> io::Result<()> {
    let method = if relay.username.is_empty() { 0 } else { 2 };
    stream.write_all(&[5, 1, method]).await?;
    stream.flush().await?;
    let mut reply = [0; 2];
    stream.read_exact(&mut reply).await?;
    if reply != [5, method] {
        return Err(invalid("SOCKS5 authentication method rejected"));
    }
    if method == 2 {
        let user = relay.username.as_bytes();
        let pass = relay.password.as_bytes();
        let len = |bytes: &[u8]| {
            u8::try_from(bytes.len()).map_err(|_| invalid("SOCKS5 credentials too long"))
        };
        let mut credentials = vec![1, len(user)?];
        credentials.extend_from_slice(user);
        credentials.push(len(pass)?);
        credentials.extend_from_slice(pass);
        stream.write_all(&credentials).await?;
        stream.flush().await?;
        stream.read_exact(&mut reply).await?;
        if reply != [1, 0] {
            return Err(invalid("SOCKS5 credentials rejected"));
        }
    }

    let mut request = vec![5, 1, 0, 3, host.len() as u8];
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&443_u16.to_be_bytes());
    stream.write_all(&request).await?;
    stream.flush().await?;
    let mut header = [0; 4];
    stream.read_exact(&mut header).await?;
    if header[..3] != [5, 0, 0] {
        return Err(invalid("SOCKS5 connect rejected"));
    }
    let length = match header[3] {
        1 => 4,
        3 => usize::from(stream.read_u8().await?),
        4 => 16,
        _ => return Err(invalid("Invalid SOCKS5 address type")),
    };
    let mut address = [0; 257];
    stream.read_exact(&mut address[..length + 2]).await?;
    Ok(())
}

async fn open_tls<T: AsyncRead + AsyncWrite + Unpin>(
    stream: T,
    host: &str,
) -> io::Result<tokio_rustls::client::TlsStream<T>> {
    let name = ServerName::try_from(host.to_owned()).map_err(|_| invalid("Invalid TLS host"))?;
    TLS.connect(name, stream).await
}

pub(crate) async fn check(relay: Option<&Relay>) -> io::Result<()> {
    let host = "discord.com";
    timeout(CONNECT_TIMEOUT, async {
        let mut stream = connect(relay, host, None).await?;
        if relay.is_some() {
            open_tls(stream, host).await?;
        } else {
            let (client, mut bridge) = aio::duplex(32 * 1024);
            let forward = async {
                direct::forward_hello(&mut bridge, &mut stream, host).await?;
                aio::copy_bidirectional(&mut bridge, &mut stream).await?;
                Ok::<(), io::Error>(())
            };
            tokio::select! {
                result = open_tls(client, host) => { result?; }
                result = forward => {
                    result?;
                    return Err(invalid("Прямое соединение закрылось до завершения TLS."));
                }
            }
        }
        Ok(())
    })
    .await?
}

async fn read_header(stream: &mut (impl AsyncRead + Unpin)) -> io::Result<String> {
    let mut header = Vec::new();
    while header.len() < MAX_HEADER {
        header.push(stream.read_u8().await?);
        if header.ends_with(b"\r\n\r\n") {
            return String::from_utf8(header).map_err(|_| invalid("Invalid proxy header"));
        }
    }
    Err(invalid("Proxy header too large"))
}

async fn response(stream: &mut TcpStream, status: &str) -> io::Result<()> {
    stream
        .write_all(format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\n\r\n").as_bytes())
        .await
}

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_only_discord_connect_targets() {
        assert_eq!(
            connect_host("CONNECT gateway.discord.gg:443 HTTP/1.1\r\n\r\n").as_deref(),
            Some("gateway.discord.gg")
        );
        for request in [
            "CONNECT evil-discord.gg:443 HTTP/1.1",
            "CONNECT discord.com:80 HTTP/1.1",
            "CONNECT localhost:443 HTTP/1.1",
            "GET discord.com:443 HTTP/1.1",
        ] {
            assert!(connect_host(request).is_none(), "{request}");
        }
        for ip in [
            "127.0.0.1",
            "192.168.0.1",
            "100.64.0.1",
            "::ffff:127.0.0.1",
            "fd00::1",
        ] {
            assert!(!direct::is_public(ip.parse().unwrap()), "{ip}");
        }
    }

    #[test]
    fn socks_relay_authenticates_and_tunnels_through_listener() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            timeout(Duration::from_secs(5), async {
                let relay = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
                let relay_port = relay.local_addr().unwrap().port();
                let peer = tokio::spawn(async move {
                    let (mut socket, _) = relay.accept().await.unwrap();
                    let mut greeting = [0; 3];
                    socket.read_exact(&mut greeting).await.unwrap();
                    assert_eq!(greeting, [5, 1, 2]);
                    socket.write_all(&[5, 2]).await.unwrap();
                    let mut auth = [0; 11];
                    socket.read_exact(&mut auth).await.unwrap();
                    assert_eq!(&auth, b"\x01\x04user\x04pass");
                    socket.write_all(&[1, 0]).await.unwrap();
                    let mut request = [0; 18];
                    socket.read_exact(&mut request).await.unwrap();
                    assert_eq!(&request, b"\x05\x01\x00\x03\x0bdiscord.com\x01\xbb");
                    socket
                        .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0])
                        .await
                        .unwrap();
                    let mut data = [0; 4];
                    socket.read_exact(&mut data).await.unwrap();
                    socket.write_all(&data).await.unwrap();
                });

                let route = Arc::new(RwLock::new(Route {
                    enabled: true,
                    relay: Some(Relay {
                        protocol: Protocol::Socks5,
                        host: "127.0.0.1".into(),
                        port: relay_port,
                        username: "user".into(),
                        password: "pass".into(),
                    }),
                }));
                let (port, listener) = listen(Arc::clone(&route)).await.unwrap();
                let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, port))
                    .await
                    .unwrap();
                client
                    .write_all(b"CONNECT discord.com:443 HTTP/1.1\r\n\r\n")
                    .await
                    .unwrap();
                assert!(
                    read_header(&mut client)
                        .await
                        .unwrap()
                        .starts_with("HTTP/1.1 200 ")
                );
                client.write_all(b"ping").await.unwrap();
                let mut reply = [0; 4];
                client.read_exact(&mut reply).await.unwrap();
                assert_eq!(&reply, b"ping");
                peer.await.unwrap();

                route.write().await.enabled = false;
                let mut disabled = TcpStream::connect((Ipv4Addr::LOCALHOST, port))
                    .await
                    .unwrap();
                assert!(
                    read_header(&mut disabled)
                        .await
                        .unwrap()
                        .starts_with("HTTP/1.1 503 ")
                );
                listener.abort();
            })
            .await
            .unwrap();
        });
    }
}
