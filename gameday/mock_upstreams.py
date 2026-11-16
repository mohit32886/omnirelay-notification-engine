#!/usr/bin/env python3
"""High-concurrency HTTP/1.1 keep-alive mock upstream simulating SendGrid v3, AWS SESv2, OAuth2, and Webhooks."""

import argparse
import asyncio
import json
import uuid


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, state: dict) -> None:
    try:
        while True:
            try:
                raw_head = await reader.readuntil(b"\r\n\r\n")
            except asyncio.IncompleteReadError:
                break

            head_text = raw_head.decode("utf-8", errors="ignore")
            lines = head_text.split("\r\n")
            if not lines:
                break

            parts = lines[0].split(" ")
            method = parts[0] if len(parts) > 0 else "GET"
            path = parts[1] if len(parts) > 1 else "/"

            content_length = 0
            close_conn = False
            for line in lines[1:]:
                low = line.lower()
                if low.startswith("content-length:"):
                    try:
                        content_length = int(line.split(":", 1)[1].strip())
                    except ValueError:
                        content_length = 0
                elif low.startswith("connection:") and "close" in low:
                    close_conn = True

            if content_length > 0:
                await reader.readexactly(content_length)

            if path.startswith("/v3/mail/send"):
                if state["sendgrid_blackout"]:
                    body = json.dumps({"errors": [{"message": "Simulated SendGrid 503 blackout"}]}).encode()
                    status = "503 Service Unavailable"
                    extra_headers = ""
                else:
                    body = b"{}"
                    status = "202 Accepted"
                    extra_headers = f"X-Message-Id: sg-{uuid.uuid4()}\r\n"
            elif path.startswith("/v2/email/outbound-emails"):
                body = json.dumps({"MessageId": f"ses-{uuid.uuid4()}"}).encode()
                status = "200 OK"
                extra_headers = ""
            elif path.startswith("/oauth2/token"):
                body = json.dumps({
                    "access_token": f"oauth2-token-{uuid.uuid4()}",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }).encode()
                status = "200 OK"
                extra_headers = ""
            elif path.startswith("/admin/blackout/sendgrid/on"):
                state["sendgrid_blackout"] = True
                body = json.dumps({"sendgrid_blackout": True}).encode()
                status = "200 OK"
                extra_headers = ""
            elif path.startswith("/admin/blackout/sendgrid/off"):
                state["sendgrid_blackout"] = False
                body = json.dumps({"sendgrid_blackout": False}).encode()
                status = "200 OK"
                extra_headers = ""
            else:
                body = json.dumps({"status": "ok", "method": method, "path": path}).encode()
                status = "200 OK"
                extra_headers = ""

            conn_header = "close" if close_conn else "keep-alive"
            response = (
                f"HTTP/1.1 {status}\r\n"
                f"Content-Type: application/json\r\n"
                f"Content-Length: {len(body)}\r\n"
                f"{extra_headers}"
                f"Connection: {conn_header}\r\n\r\n"
            ).encode() + body

            writer.write(response)
            await writer.drain()
            if close_conn:
                break
    except Exception:
        pass
    finally:
        writer.close()


async def main(port: int) -> None:
    state = {"sendgrid_blackout": False}
    server = await asyncio.start_server(
        lambda r, w: handle_client(r, w, state),
        host="0.0.0.0",
        port=port,
        backlog=4096,
    )
    print(f"OmniRelay mock upstreams listening on 0.0.0.0:{port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    asyncio.run(main(args.port))
