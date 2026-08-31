#!/usr/bin/env python3
"""Minimal WebDAV client for encrypted WAO backup artifacts.

The client intentionally depends only on the Python standard library so the
backup path does not depend on an interactive desktop client or a mutable
third-party package installation.
"""

from __future__ import annotations

import argparse
import base64
import http.client
import os
import ssl
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit


class WebDavError(RuntimeError):
    pass


def load_config(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise WebDavError(f"Invalid configuration line {number}")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


class WebDavClient:
    def __init__(self, base_url: str, username: str, password: str, allow_http: bool = False):
        parsed = urlsplit(base_url)
        if parsed.scheme not in ({"http", "https"} if allow_http else {"https"}):
            raise WebDavError("WebDAV URL must use HTTPS")
        if not parsed.hostname or parsed.query or parsed.fragment:
            raise WebDavError("Invalid WebDAV URL")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port
        self.base_path = parsed.path.rstrip("/") or "/"
        token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
        self.authorization = f"Basic {token}"

    def _connection(self) -> http.client.HTTPConnection:
        if self.scheme == "https":
            return http.client.HTTPSConnection(
                self.host, self.port, timeout=300, context=ssl.create_default_context()
            )
        return http.client.HTTPConnection(self.host, self.port, timeout=300)

    def _target(self, remote_path: str, directory: bool = False) -> str:
        clean = remote_path.strip("/")
        parts = [part for part in clean.split("/") if part]
        if any(part in {".", ".."} for part in parts):
            raise WebDavError("Remote path traversal is not allowed")
        encoded = "/".join(quote(part, safe="") for part in parts)
        target = self.base_path
        if encoded:
            target = f"{target.rstrip('/')}/{encoded}"
        if directory and not target.endswith("/"):
            target += "/"
        return target

    def _request(
        self,
        method: str,
        remote_path: str,
        *,
        body: bytes | None = None,
        file_path: Path | None = None,
        headers: dict[str, str] | None = None,
        directory: bool = False,
        expected: set[int],
        attempts: int = 4,
    ) -> tuple[int, dict[str, str], bytes]:
        if body is not None and file_path is not None:
            raise ValueError("body and file_path are mutually exclusive")
        target = self._target(remote_path, directory=directory)
        last_error: Exception | None = None
        for attempt in range(1, attempts + 1):
            connection = self._connection()
            try:
                request_headers = {
                    "Authorization": self.authorization,
                    "User-Agent": "WAO-Offsite-Backup/1",
                }
                if headers:
                    request_headers.update(headers)
                if file_path is not None:
                    size = file_path.stat().st_size
                    connection.putrequest(method, target)
                    for key, value in request_headers.items():
                        connection.putheader(key, value)
                    connection.putheader("Content-Length", str(size))
                    connection.endheaders()
                    with file_path.open("rb") as source:
                        while chunk := source.read(1024 * 1024):
                            connection.send(chunk)
                    response = connection.getresponse()
                else:
                    connection.request(method, target, body=body, headers=request_headers)
                    response = connection.getresponse()
                payload = response.read(2 * 1024 * 1024)
                response_headers = {key.lower(): value for key, value in response.getheaders()}
                if response.status not in expected:
                    detail = payload.decode("utf-8", errors="replace")[:500]
                    raise WebDavError(f"WebDAV {method} failed with HTTP {response.status}: {detail}")
                return response.status, response_headers, payload
            except (OSError, http.client.HTTPException, WebDavError) as exc:
                last_error = exc
                if attempt == attempts:
                    break
                time.sleep(attempt * 2)
            finally:
                connection.close()
        raise WebDavError(str(last_error))

    def ensure_dir(self, remote_path: str) -> None:
        current: list[str] = []
        for part in (part for part in remote_path.strip("/").split("/") if part):
            current.append(part)
            self._request(
                "MKCOL",
                "/".join(current),
                expected={201, 405},
                attempts=2,
            )

    def upload(self, local_path: Path, remote_path: str) -> None:
        expected_size = local_path.stat().st_size
        existing_size = self.stat_size(remote_path, missing_ok=True)
        if existing_size == expected_size:
            return
        self._request("PUT", remote_path, file_path=local_path, expected={200, 201, 204})
        actual_size = self.stat_size(remote_path)
        if actual_size != expected_size:
            raise WebDavError(
                f"Remote size mismatch for {remote_path}: expected {expected_size}, got {actual_size}"
            )

    def download(self, remote_path: str, local_path: Path) -> None:
        target = self._target(remote_path)
        last_error: Exception | None = None
        for attempt in range(1, 5):
            connection = self._connection()
            try:
                connection.request(
                    "GET",
                    target,
                    headers={
                        "Authorization": self.authorization,
                        "User-Agent": "WAO-Offsite-Backup/1",
                    },
                )
                response = connection.getresponse()
                if response.status != 200:
                    detail = response.read(500).decode("utf-8", errors="replace")
                    raise WebDavError(f"WebDAV GET failed with HTTP {response.status}: {detail}")
                expected = response.getheader("Content-Length")
                with local_path.open("wb") as target_file:
                    while chunk := response.read(1024 * 1024):
                        target_file.write(chunk)
                if expected is not None and local_path.stat().st_size != int(expected):
                    raise WebDavError(f"Downloaded size mismatch for {remote_path}")
                return
            except (OSError, http.client.HTTPException, WebDavError) as exc:
                last_error = exc
                local_path.unlink(missing_ok=True)
                if attempt < 4:
                    time.sleep(attempt * 2)
            finally:
                connection.close()
        raise WebDavError(str(last_error))

    def stat_size(self, remote_path: str, missing_ok: bool = False) -> int | None:
        expected = {200, 204, 405, 501}
        if missing_ok:
            expected.add(404)
        status, headers, _ = self._request("HEAD", remote_path, expected=expected, attempts=2)
        if status == 404:
            return None
        value = headers.get("content-length")
        if status in {200, 204} and value is not None:
            return int(value)
        propfind_expected = {207}
        if missing_ok:
            propfind_expected.add(404)
        body = b'<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getcontentlength/></prop></propfind>'
        propfind_status, _, payload = self._request(
            "PROPFIND",
            remote_path,
            body=body,
            headers={"Depth": "0", "Content-Type": "application/xml"},
            expected=propfind_expected,
            attempts=2,
        )
        if propfind_status == 404:
            return None
        root = ET.fromstring(payload)
        content_length = root.findtext(".//{DAV:}getcontentlength")
        if content_length is None:
            raise WebDavError(f"WebDAV omitted content length for {remote_path}")
        return int(content_length)

    def list_dirs(self, remote_path: str) -> list[str]:
        body = b'<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>'
        _, _, payload = self._request(
            "PROPFIND",
            remote_path,
            body=body,
            headers={"Depth": "1", "Content-Type": "application/xml"},
            directory=True,
            expected={207},
        )
        result: list[str] = []
        root = ET.fromstring(payload)
        requested = self._target(remote_path, directory=True).rstrip("/")
        for response in root.findall("{DAV:}response"):
            href = response.findtext("{DAV:}href", default="")
            href_path = unquote(urlsplit(href).path).rstrip("/")
            if href_path == unquote(requested):
                continue
            resource_type = response.find(".//{DAV:}resourcetype")
            if resource_type is None or resource_type.find("{DAV:}collection") is None:
                continue
            name = href_path.rsplit("/", 1)[-1]
            if name and "/" not in name:
                result.append(name)
        return sorted(set(result))

    def delete(self, remote_path: str) -> None:
        self._request("DELETE", remote_path, expected={200, 202, 204, 404}, directory=True)


def build_client(config_path: Path, allow_http: bool) -> tuple[WebDavClient, dict[str, str]]:
    config = load_config(config_path)
    required = [
        "WAO_BACKUP_WEBDAV_URL",
        "WAO_BACKUP_WEBDAV_USER",
        "WAO_BACKUP_WEBDAV_PASSWORD",
    ]
    missing = [key for key in required if not config.get(key)]
    if missing:
        raise WebDavError(f"Missing configuration keys: {', '.join(missing)}")
    client = WebDavClient(
        config["WAO_BACKUP_WEBDAV_URL"],
        config["WAO_BACKUP_WEBDAV_USER"],
        config["WAO_BACKUP_WEBDAV_PASSWORD"],
        allow_http=allow_http,
    )
    return client, config


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--allow-http", action="store_true")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("check")
    ensure = subparsers.add_parser("ensure-dir")
    ensure.add_argument("remote_path")
    upload = subparsers.add_parser("upload")
    upload.add_argument("local_path", type=Path)
    upload.add_argument("remote_path")
    download = subparsers.add_parser("download")
    download.add_argument("remote_path")
    download.add_argument("local_path", type=Path)
    stat = subparsers.add_parser("stat")
    stat.add_argument("remote_path")
    listing = subparsers.add_parser("list-dirs")
    listing.add_argument("remote_path")
    delete = subparsers.add_parser("delete")
    delete.add_argument("remote_path")
    args = parser.parse_args()

    try:
        client, config = build_client(args.config, args.allow_http)
        remote_root = config.get("WAO_BACKUP_REMOTE_ROOT", "WAO-backups").strip("/")
        if args.command == "check":
            client.ensure_dir(remote_root)
            client.list_dirs(remote_root)
        elif args.command == "ensure-dir":
            client.ensure_dir(args.remote_path)
        elif args.command == "upload":
            client.upload(args.local_path, args.remote_path)
        elif args.command == "download":
            client.download(args.remote_path, args.local_path)
        elif args.command == "stat":
            print(client.stat_size(args.remote_path))
        elif args.command == "list-dirs":
            print("\n".join(client.list_dirs(args.remote_path)))
        elif args.command == "delete":
            client.delete(args.remote_path)
    except (OSError, ValueError, ET.ParseError, WebDavError) as exc:
        print(f"WebDAV operation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
