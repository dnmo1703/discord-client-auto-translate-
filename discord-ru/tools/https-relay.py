#!/usr/bin/env python3
"""Authenticated TLS CONNECT relay for Discord HTTPS and WebSocket traffic."""
import argparse
import asyncio
import base64
import getpass
import hmac
import ipaddress
import os
import re
import socket
import ssl

DOMAINS = ("discord.com", "discordapp.com", "discordapp.net", "discord.gg",
           "discord.media", "discordcdn.com")
TARGET = re.compile(rb"([A-Za-z0-9.-]{1,253}):443")
HEADER_LIMIT = 8192


def allowed(host):
    return any(host == domain or host.endswith("." + domain) for domain in DOMAINS)


async def send(writer, status, headers=b""):
    writer.write(b"HTTP/1.1 " + status + b"\r\n" + headers + b"\r\n")
    await writer.drain()


async def open_remote(host):
    loop = asyncio.get_running_loop()
    addresses = await asyncio.wait_for(
        loop.getaddrinfo(host, 443, type=socket.SOCK_STREAM), 10)
    for family, _, _, _, address in addresses:
        if not ipaddress.ip_address(address[0]).is_global:
            continue
        try:
            return await asyncio.wait_for(
                asyncio.open_connection(address[0], 443, family=family), 5)
        except (OSError, TimeoutError):
            continue
    raise OSError("Discord destination unavailable")


async def copy(reader, writer):
    while block := await asyncio.wait_for(reader.read(65536), 300):
        writer.write(block)
        await writer.drain()
    if writer.can_write_eof():
        writer.write_eof()


class Relay:
    def __init__(self, authorization):
        self.authorization = authorization
        self.active = 0

    async def handle(self, reader, writer):
        if self.active >= 128:
            writer.close()
            return
        self.active += 1
        peer = None
        transfers = []
        try:
            raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
            first, *headers = raw.split(b"\r\n")
            split = first.split(b" ")
            target = TARGET.fullmatch(split[1]) if len(split) == 3 else None
            if not target or split[0] != b"CONNECT" or split[2] not in (b"HTTP/1.0", b"HTTP/1.1"):
                await send(writer, b"400 Bad Request", b"Content-Length: 0\r\n")
                return
            auth = [value.strip() for field in headers if b":" in field
                    for key, value in [field.split(b":", 1)]
                    if key.lower() == b"proxy-authorization"]
            if len(auth) != 1 or not hmac.compare_digest(auth[0], self.authorization):
                await send(writer, b"407 Proxy Authentication Required",
                           b'Proxy-Authenticate: Basic realm="Discord"\r\nContent-Length: 0\r\n')
                return
            host = target[1].decode("ascii").lower()
            if not allowed(host):
                await send(writer, b"403 Forbidden", b"Content-Length: 0\r\n")
                return
            try:
                remote, peer = await open_remote(host)
            except (OSError, TimeoutError):
                await send(writer, b"502 Bad Gateway", b"Content-Length: 0\r\n")
                return
            await send(writer, b"200 Connection Established")
            transfers = [
                asyncio.create_task(copy(reader, peer)),
                asyncio.create_task(copy(remote, writer)),
            ]
            await asyncio.wait(transfers, return_when=asyncio.FIRST_COMPLETED)
        except (OSError, TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        finally:
            for task in transfers:
                task.cancel()
            await asyncio.gather(*transfers, return_exceptions=True)
            if peer is not None:
                peer.close()
            writer.close()
            self.active -= 1


async def main(args, password):
    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
    ssl_context.load_cert_chain(args.cert, args.key)
    auth = base64.b64encode(f"{args.user}:{password}".encode("utf-8"))
    relay = Relay(b"Basic " + auth)
    async with await asyncio.start_server(
        relay.handle, args.bind, args.port, ssl=ssl_context,
        limit=HEADER_LIMIT, ssl_handshake_timeout=10,
    ) as server:
        print(f"Discord relay listening on {args.bind}:{args.port}", flush=True)
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--user", required=True)
    args = parser.parse_args()
    if not args.user or ":" in args.user:
        parser.error("--user must be non-empty and contain no colon")
    password = os.environ.get("DISCORD_RELAY_PASSWORD") or getpass.getpass("Proxy password: ")
    if not password:
        parser.error("A proxy password is required")
    try:
        asyncio.run(main(args, password))
    except KeyboardInterrupt:
        pass
