#!/usr/bin/env python3
"""Package and atomically install immutable, checksum-verified public assets."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile

MANIFEST = "release-manifest.json"
MAX_BYTES = 1024 * 1024 * 1024


def release_id(value):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Release must be a full Git SHA")
    return value


def safe_name(name):
    p = PurePosixPath(name)
    if (not name or p.is_absolute() or "\\" in name or ":" in name
            or any(part in ("", ".", "..") or part.startswith(".") for part in name.split("/"))):
        raise ValueError("Unsafe public asset path")
    if p.suffix.lower() in (".map", ".pem", ".key", ".p12", ".sql"):
        raise ValueError("Private or source-map file is not a public asset")
    return name


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pack(source, output, release):
    release_id(release)
    source = Path(source).resolve()
    files = {}
    total = 0
    with tarfile.open(output, "w:gz") as archive:
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise ValueError("Symlinks are forbidden")
            if not path.is_file():
                continue
            name = safe_name(path.relative_to(source).as_posix())
            if name == MANIFEST:
                raise ValueError("Build output contains a reserved manifest")
            data = path.read_bytes()
            total += len(data)
            if total > MAX_BYTES:
                raise ValueError("Public release exceeds size budget")
            files[name] = {"sha256": digest(data), "size": len(data)}
            add_file(archive, name, data)
        if "index.html" not in files or not any(n.startswith("assets/") for n in files):
            raise ValueError("Incomplete frontend build")
        manifest = json.dumps({"release": release, "files": files}, sort_keys=True).encode()
        add_file(archive, MANIFEST, manifest)
    return len(files)


def add_file(archive, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    archive.addfile(info, io.BytesIO(data))


def install(archive_path, root, release):
    release_id(release)
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".incoming-", dir=root))
    try:
        seen = set()
        total = 0
        with tarfile.open(archive_path, "r:gz") as archive:
            for member in archive:
                name = safe_name(member.name)
                total += member.size
                if not member.isfile() or name in seen or total > MAX_BYTES:
                    raise ValueError("Invalid archive entry or size budget")
                seen.add(name)
                target = stage / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                target.chmod(0o644)
        manifest = json.loads((stage / MANIFEST).read_text())
        if manifest["release"] != release or set(manifest["files"]) != seen - {MANIFEST}:
            raise ValueError("Release identity or file inventory mismatch")
        if "index.html" not in seen or not any(n.startswith("assets/") for n in seen):
            raise ValueError("Incomplete frontend build")
        for name, expected in manifest["files"].items():
            data = (stage / safe_name(name)).read_bytes()
            if expected != {"sha256": digest(data), "size": len(data)}:
                raise ValueError("Asset checksum mismatch")
        final = root / release
        if final.exists():
            if final.is_symlink() or (final / MANIFEST).read_bytes() != (stage / MANIFEST).read_bytes():
                raise ValueError("Refusing to overwrite an immutable release")
            for name, expected in manifest["files"].items():
                if digest((final / name).read_bytes()) != expected["sha256"]:
                    raise ValueError("Existing release is damaged; manual recovery required")
            return "already-installed"
        for directory, _, _ in os.walk(stage):
            Path(directory).chmod(0o755)
        stage.rename(final)
        return "installed"
    finally:
        if stage.exists():
            shutil.rmtree(stage)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("pack", "install"))
    parser.add_argument("source")
    parser.add_argument("destination")
    parser.add_argument("release")
    args = parser.parse_args()
    print((pack if args.action == "pack" else install)(args.source, args.destination, args.release))
