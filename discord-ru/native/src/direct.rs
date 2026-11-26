use reqwest::{Client, redirect::Policy};
use serde::Deserialize;
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::LazyLock;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, lookup_host};
use tokio::sync::Mutex;
use tokio::time::timeout;

type CacheEntry = (Instant, Vec<SocketAddr>);
static CACHE: LazyLock<Mutex<HashMap<String, CacheEntry>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static DNS: LazyLock<Result<Client, reqwest::Error>> = LazyLock::new(|| {
    Client::builder()
        .no_proxy()
        .https_only(true)
        .redirect(Policy::none())
        .resolve("cloudflare-dns.com", SocketAddr::from(([1, 1, 1, 1], 443)))
        .timeout(Duration::from_secs(2))
        .build()
});

#[derive(Deserialize)]
struct DnsAnswer {
    #[serde(rename = "type")]
    kind: u16,
    data: String,
    #[serde(rename = "TTL")]
    ttl: u64,
}

#[derive(Deserialize)]
struct DnsResponse {
    #[serde(rename = "Status")]
    status: u16,
    #[serde(rename = "Answer", default)]
    answers: Vec<DnsAnswer>,
}

async fn lookup_doh(host: &str, family: &str, answer_kind: u16) -> Vec<(SocketAddr, u64)> {
    let Ok(client) = DNS.as_ref() else {
        return Vec::new();
    };
    let request = client
        .get("https://cloudflare-dns.com/dns-query")
        .query(&[("name", host), ("type", family)])
        .header("accept", "application/dns-json");
    let Ok(response) = request
        .send()
        .await
        .and_then(|response| response.error_for_status())
    else {
        return Vec::new();
    };
    let Ok(reply) = response.json::<DnsResponse>().await else {
        return Vec::new();
    };
    if reply.status != 0 {
        return Vec::new();
    }
    reply
        .answers
        .into_iter()
        .filter(|record| record.kind == answer_kind)
        .filter_map(|record| {
            let ip: IpAddr = record.data.parse().ok()?;
            is_public(ip).then_some((SocketAddr::new(ip, 443), record.ttl))
        })
        .collect()
}

async fn addresses(host: &str) -> Vec<SocketAddr> {
    let now = Instant::now();
    if let Some((expires, values)) = CACHE.lock().await.get(host) {
        if *expires > now {
            return values.clone();
        }
    }
    let (v4, v6) = tokio::join!(lookup_doh(host, "A", 1), lookup_doh(host, "AAAA", 28));
    let mut records = Vec::new();
    for index in 0..v4.len().max(v6.len()) {
        records.extend(v4.get(index).copied());
        records.extend(v6.get(index).copied());
    }
    let ttl = records
        .iter()
        .map(|(_, ttl)| *ttl)
        .min()
        .unwrap_or(0)
        .min(60);
    let values: Vec<_> = records.into_iter().map(|(address, _)| address).collect();
    if ttl > 0 {
        let mut cache = CACHE.lock().await;
        cache.retain(|_, (expires, _)| *expires > now);
        if cache.len() < 64 {
            cache.insert(
                host.into(),
                (now + Duration::from_secs(ttl), values.clone()),
            );
        }
    }
    values
}

pub(crate) async fn connect(host: &str) -> io::Result<TcpStream> {
    let encrypted_dns = addresses(host).await;
    for address in encrypted_dns.iter().take(4) {
        if let Ok(Ok(stream)) =
            timeout(Duration::from_millis(1100), TcpStream::connect(address)).await
        {
            stream.set_nodelay(true)?;
            return Ok(stream);
        }
    }

    let mut last = io::Error::new(
        io::ErrorKind::NotConnected,
        "Не найден доступный IP-адрес Discord.",
    );
    for address in lookup_host((host, 443))
        .await?
        .filter(|addr| is_public(addr.ip()))
    {
        if encrypted_dns
            .iter()
            .take(4)
            .any(|checked| checked == &address)
        {
            continue;
        }
        match timeout(Duration::from_millis(1100), TcpStream::connect(address)).await {
            Ok(Ok(stream)) => {
                stream.set_nodelay(true)?;
                return Ok(stream);
            }
            Ok(Err(error)) => last = error,
            Err(error) => last = error.into(),
        }
    }
    Err(last)
}

pub(crate) fn is_public(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => {
            let [a, b, _, _] = ip.octets();
            !ip.is_private()
                && !ip.is_loopback()
                && !ip.is_link_local()
                && !ip.is_multicast()
                && !ip.is_broadcast()
                && a != 0
                && a < 240
                && !(a == 100 && (64..=127).contains(&b))
        }
        IpAddr::V6(ip) => {
            if let Some(ip) = ip.to_ipv4_mapped() {
                return is_public(IpAddr::V4(ip));
            }
            let first = ip.segments()[0];
            !ip.is_unspecified()
                && !ip.is_loopback()
                && !ip.is_multicast()
                && first & 0xffc0 != 0xfe80
                && first & 0xfe00 != 0xfc00
        }
    }
}

pub(crate) async fn forward_hello(
    client: &mut (impl AsyncRead + Unpin),
    target: &mut (impl AsyncWrite + Unpin),
    host: &str,
) -> io::Result<()> {
    let mut header = [0; 5];
    client.read_exact(&mut header).await?;
    let length = usize::from(u16::from_be_bytes([header[3], header[4]]));
    if header[0] != 22 || header[1] != 3 || length == 0 || length > 16_384 {
        return target.write_all(&header).await;
    }
    let mut payload = vec![0; length];
    client.read_exact(&mut payload).await?;
    let Some(offset) = sni_split(&payload, host) else {
        target.write_all(&header).await?;
        return target.write_all(&payload).await;
    };
    for part in [&payload[..offset], &payload[offset..]] {
        header[3..].copy_from_slice(&(part.len() as u16).to_be_bytes());
        target.write_all(&header).await?;
        target.write_all(part).await?;
    }
    target.flush().await
}

fn sni_split(payload: &[u8], host: &str) -> Option<usize> {
    if payload.first() != Some(&1) {
        return None;
    }
    let size = payload.get(1..4)?;
    let length = (usize::from(size[0]) << 16) | (usize::from(size[1]) << 8) | usize::from(size[2]);
    let hello = payload.get(..4 + length)?;
    let session = usize::from(*hello.get(38)?);
    let ciphers_at = 39 + session;
    let ciphers = two_bytes(hello, ciphers_at)?;
    let compression_at = ciphers_at + 2 + ciphers;
    let compression = usize::from(*hello.get(compression_at)?);
    let extensions = compression_at + 1 + compression;
    let end = extensions + 2 + two_bytes(hello, extensions)?;
    let hello = hello.get(..end)?;
    let mut at = extensions + 2;
    while at < end {
        let kind = two_bytes(hello, at)?;
        let next = at + 4 + two_bytes(hello, at + 2)?;
        let extension = hello.get(at + 4..next)?;
        if kind == 0 {
            let names = extension.get(2..2 + two_bytes(extension, 0)?)?;
            let mut name = 0;
            while name < names.len() {
                let len = two_bytes(names, name + 1)?;
                let value = names.get(name + 3..name + 3 + len)?;
                if names[name] == 0 && len > 1 && value.eq_ignore_ascii_case(host.as_bytes()) {
                    return Some(at + 6 + name + 3 + len / 2);
                }
                name += 3 + len;
            }
        }
        at = next;
    }
    None
}

fn two_bytes(bytes: &[u8], offset: usize) -> Option<usize> {
    let pair = bytes.get(offset..offset + 2)?;
    Some(usize::from(u16::from_be_bytes([pair[0], pair[1]])))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::{ClientConfig, ClientConnection, RootCertStore};

    fn client_hello(host: &str) -> Vec<u8> {
        let config = ClientConfig::builder()
            .with_root_certificates(RootCertStore::empty())
            .with_no_client_auth();
        let name = rustls::pki_types::ServerName::try_from(host.to_owned()).unwrap();
        let mut client = ClientConnection::new(std::sync::Arc::new(config), name).unwrap();
        let mut raw = Vec::new();
        client.write_tls(&mut raw).unwrap();
        raw
    }

    #[tokio::test]
    async fn splits_discord_client_hello_without_changing_handshake_bytes() {
        let hello = client_hello("gateway.discord.gg");
        let first_length = two_bytes(&hello, 3).unwrap();
        let (record, rest) = hello.split_at(5 + first_length);
        let host = b"gateway.discord.gg";
        let sni = record[5..]
            .windows(host.len())
            .position(|slice| slice == host)
            .unwrap();

        let mut input = hello.as_slice();
        let mut output = Vec::new();
        forward_hello(&mut input, &mut output, "gateway.discord.gg")
            .await
            .unwrap();

        assert_eq!(input, rest);
        let split = two_bytes(&output, 3).unwrap();
        assert!(split > sni && split < sni + host.len());
        let next = 5 + split;
        assert_eq!(&output[..3], &hello[..3]);
        assert_eq!(&output[next..next + 3], &hello[..3]);
        assert_eq!(two_bytes(&output, next + 3).unwrap(), first_length - split);
        assert_eq!(
            [&output[5..next], &output[next + 5..]].concat(),
            record[5..]
        );

        let mut input = hello.as_slice();
        let mut unchanged = Vec::new();
        forward_hello(&mut input, &mut unchanged, "discord.com")
            .await
            .unwrap();
        assert_eq!(unchanged, record);
    }
}
