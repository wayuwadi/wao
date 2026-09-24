#!/usr/bin/env python3
"""Publish a browsable, one-way WAO content copy to the dedicated WebDAV folder.

The manifest records byte hashes so unchanged files never leave Linux2 again.
Only knowledge documents, tasks and their referenced media are exported; this
is not a database recovery archive. Encrypted recovery snapshots are untouched.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

from webdav_client import WebDavClient, build_client

SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
IMAGE_REF = re.compile(r"/api/images/([A-Za-z0-9_-]{1,64})/raw")
FILE_REF = re.compile(r"(?:/api/files/|/f/)([A-Za-z0-9_-]{1,64})(?:/download|/preview)?")
ROOT = "WAO-可读文件"


def safe_name(value: str, limit: int = 72) -> str:
    value = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", " ", value or "")
    value = re.sub(r"\s+", " ", value).strip(" .")
    value = value[:limit]
    while len(value.encode("utf-8")) > 140:
        value = value[:-1]
    return value.rstrip(" .") or "未命名"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def mysql(sql: str, *, binary: bool = False) -> bytes:
    cmd = ["mysql", "--batch", "--raw", "--skip-column-names",
           "--default-character-set=utf8mb4", "--max-allowed-packet=128M",
           "-uroot", "-h127.0.0.1",
           "-P", os.environ.get("MYSQL_PORT", "3306"), "wao", "-e", sql]
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=False, timeout=600)
    if result.returncode:
        raise RuntimeError("MySQL read failed: " + result.stderr.decode(errors="replace")[:300])
    return result.stdout if binary else result.stdout


def rows(sql: str) -> list[dict]:
    payload = mysql(sql).decode("utf-8")
    output = []
    for number, line in enumerate(payload.splitlines(), 1):
        if not line:
            continue
        try:
            output.append(json.loads(base64.b64decode(line)))
        except (json.JSONDecodeError, ValueError) as exc:
            raise RuntimeError(f"Invalid MySQL JSON row {number}, {len(line)} characters: {exc}") from exc
    return output


def content_text(raw: object) -> str:
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    return json.dumps(raw, ensure_ascii=False, indent=2)


def legacy_blocks(raw: object) -> str:
    if not isinstance(raw, list):
        return content_text(raw)
    output = []
    for block in raw:
        if not isinstance(block, dict):
            output.append(content_text(block))
            continue
        for key, value in block.items():
            if key in {"p", "paragraph", "text"}:
                output.append(content_text(value))
            elif key in {"h1", "h2", "h3"}:
                output.append("#" * int(key[1]) + " " + content_text(value))
            elif key in {"ul", "ol"} and isinstance(value, list):
                output.extend(("- " if key == "ul" else f"{index}. ") + content_text(item)
                              for index, item in enumerate(value, 1))
            elif key == "quote":
                output.append("> " + content_text(value))
            else:
                output.append(f"{key}：{content_text(value)}")
    return "\n\n".join(output)


def body_text(record: dict, primary: str, fallback: str = "") -> str:
    body = record.get(primary)
    if body:
        return content_text(body)
    if fallback and record.get(fallback):
        return legacy_blocks(record[fallback])
    return "（无正文）"


def text_file(title: str, metadata: list[tuple[str, object]], body: str,
              extra: list[tuple[str, object]] = ()) -> bytes:
    lines = [title, "=" * min(60, len(title)), ""]
    lines.extend(f"{label}：{content_text(value)}" for label, value in metadata if value not in (None, ""))
    lines.extend(["", "正文", "----", body.strip(), ""])
    for label, value in extra:
        if value not in (None, "", [], {}):
            lines.extend([label, "----", content_text(value), ""])
    return "\n".join(lines).encode("utf-8")


def references(*values: object) -> tuple[set[str], set[str]]:
    raw = "\n".join(content_text(value) for value in values)
    return set(IMAGE_REF.findall(raw)), set(FILE_REF.findall(raw))


def wanted_content() -> tuple[dict[str, bytes], set[str], set[str], dict[str, int]]:
    wanted: dict[str, bytes] = {}
    image_ids: set[str] = set()
    file_ids: set[str] = set()
    counts: dict[str, int] = {}

    docs = rows("SELECT REPLACE(TO_BASE64(JSON_OBJECT('id',d.id,'title',d.title,'kb',k.name,"
                "'folder',f.name,'author',d.author_id,'updated',d.updated_at,"
                "'markdown',d.content_markdown,'blocks',d.blocks_json,"
                "'content_json',d.content_json,'tags',d.tags_json,'links',d.links_json)),CHAR(10),'') "
                "FROM doc d JOIN kb k ON k.id=d.kb_id "
                "LEFT JOIN kb_folder f ON f.id=d.folder_id "
                "WHERE d.deleted_at IS NULL AND k.deleted_at IS NULL "
                "ORDER BY d.id")
    counts["知识库与小记"] = len(docs)
    for d in docs:
        path = f"知识库与小记/{safe_name(d['kb'])}/{safe_name(d['title'])}--{d['id']}.txt"
        wanted[path] = text_file(d["title"], [("知识库", d["kb"]), ("文件夹", d["folder"]),
                                               ("作者 ID", d["author"]), ("更新", d["updated"]),
                                               ("标签", d["tags"])],
                                 body_text(d, "markdown", "blocks"),
                                 [("富文本原始结构", d["content_json"]),
                                  ("旧版原始结构", d["blocks"] if not d["markdown"] else None),
                                  ("关联", d["links"])])
        images, files = references(d["markdown"], d["blocks"], d["content_json"], d["links"])
        image_ids.update(images)
        file_ids.update(files)

    tasks = rows("SELECT REPLACE(TO_BASE64(JSON_OBJECT('id',t.id,'title',t.title,'project',p.name,"
                 "'status',t.status,'priority',t.pr,'owner',t.owner_id,"
                 "'created_by',t.created_by,'created',t.created_at,'start',t.start_date,"
                 "'due',t.due_date,'completed',t.completed_at,'waiting',t.waiting,"
                 "'description',t.description,'detail',t.detail_json,'parent',t.parent_task_id)),CHAR(10),'') "
                 "FROM task t LEFT JOIN project p ON p.id=t.project_id "
                 "WHERE t.id IS NOT NULL "
                 "ORDER BY t.id")
    counts["任务"] = len(tasks)
    for t in tasks:
        path = f"任务/{safe_name(t['project'] or '未分项目')}/{safe_name(t['title'])}--{t['id']}.txt"
        wanted[path] = text_file(t["title"], [("项目", t["project"]), ("状态", t["status"]),
                                                 ("优先级", t["priority"]), ("负责人 ID", t["owner"]),
                                                 ("创建", t["created"]), ("开始", t["start"]),
                                                 ("截止", t["due"]), ("完成", t["completed"]),
                                                 ("等待事项", t["waiting"]), ("父任务 ID", t["parent"])],
                                  body_text(t, "description"), [("任务详情", t["detail"])])
        images, files = references(t["description"], t["detail"])
        image_ids.update(images)
        file_ids.update(files)

    return wanted, image_ids, file_ids, counts


def media_metadata(table: str, ids: set[str]) -> tuple[list[dict], int]:
    if table not in {"image_blob", "file_blob"} or any(not SAFE_ID.fullmatch(i) for i in ids):
        raise ValueError("Invalid media query")
    if not ids:
        return [], 0
    result = []
    hash_expression = "COALESCE(NULLIF(content_hash,''),SHA2(content,256))" if table == "image_blob" else "SHA2(content,256)"
    ordered = sorted(ids)
    for start in range(0, len(ordered), 100):
        chunk = ordered[start:start + 100]
        values = ",".join("'" + item + "'" for item in chunk)
        result.extend(rows("SELECT REPLACE(TO_BASE64(JSON_OBJECT('id',id,'name',name,'type',content_type,"
                           f"'bytes',byte_size,'sha256',{hash_expression})),CHAR(10),'') FROM {table} "
                           f"WHERE id IN ({values}) ORDER BY id"))
    return result, len(ids - {item["id"] for item in result})


def media_path(kind: str, item: dict) -> str:
    guessed = mimetypes.guess_extension(item["type"] or "") or ""
    suffix = Path(item["name"] or "").suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,8}", suffix):
        suffix = guessed if re.fullmatch(r"\.[a-z0-9]{1,8}", guessed) else ".bin"
    return f"{kind}/{safe_name(Path(item['name'] or item['id']).stem)}--{item['id']}{suffix}"


def fetch_media(table: str, item: dict) -> bytes:
    if table not in {"image_blob", "file_blob"} or not SAFE_ID.fullmatch(item["id"]):
        raise ValueError("Invalid media ID")
    raw = mysql(f"SELECT HEX(content) FROM {table} WHERE id='{item['id']}'", binary=True).strip()
    data = bytes.fromhex(raw.decode("ascii"))
    if len(data) != item["bytes"] or digest(data) != item["sha256"]:
        raise RuntimeError("Media changed during export")
    return data


def valid_old_manifest(manifest: dict) -> tuple[dict[str, dict], dict[str, dict]]:
    if manifest.get("formatVersion") != 1 or manifest.get("scope") != "all-knowledge-tasks":
        raise RuntimeError("Cloud manifest belongs to a different export")
    files = manifest.get("files")
    pending = manifest.get("pendingDelete", {})
    if not isinstance(files, dict) or not isinstance(pending, dict):
        raise RuntimeError("Invalid cloud manifest")
    for path, meta in (files | pending).items():
        if (not isinstance(path, str) or path.startswith("/") or ".." in path.split("/")
                or path.startswith("manifest") or not isinstance(meta, dict)
                or not re.fullmatch(r"[0-9a-f]{64}", str(meta.get("sha256")))
                or not isinstance(meta.get("bytes"), int)):
            raise RuntimeError("Unsafe cloud manifest")
    return files, pending


def remote_bytes(client: WebDavClient, remote: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="wao-readable-") as directory:
        target = Path(directory) / "download"
        client.download(remote, target)
        return target.read_bytes()


def upload_bytes(client: WebDavClient, remote: str, data: bytes,
                 ensured: set[str] | None = None) -> None:
    with tempfile.TemporaryDirectory(prefix="wao-readable-") as directory:
        source = Path(directory) / "upload"
        source.write_bytes(data)
        directory_name = remote.rsplit("/", 1)[0]
        if ensured is None or directory_name not in ensured:
            client.ensure_dir(directory_name)
            if ensured is not None:
                ensured.add(directory_name)
        client.upload(source, remote, force=True)


def sync(client: WebDavClient, *, resume_initial: bool = False) -> dict:
    client.ensure_dir(ROOT)
    ensured = {ROOT}
    manifest_remote = ROOT + "/manifest.json"
    old: dict[str, dict] = {}
    pending: dict[str, dict] = {}
    manifest_exists = client.stat_size(manifest_remote, missing_ok=True) is not None
    if manifest_exists:
        old, pending = valid_old_manifest(json.loads(remote_bytes(client, manifest_remote)))
    if resume_initial and manifest_exists:
        raise RuntimeError("Initial resume cannot be used after a cloud manifest exists")
    texts, image_ids, file_ids, counts = wanted_content()
    if not texts or not counts["知识库与小记"] or not counts["任务"]:
        raise RuntimeError("Core content unexpectedly empty; refusing cloud update")
    image_meta, missing_images = media_metadata("image_blob", image_ids)
    file_meta, missing_files = media_metadata("file_blob", file_ids)
    counts["图片"] = len(image_meta)
    counts["附件"] = len(file_meta)
    counts["失效媒体引用"] = missing_images + missing_files
    print("Readable content inventory: " + json.dumps(counts, ensure_ascii=False), flush=True)
    listing = "WAO 可读内容副本\n================\n\n这个文件夹每天单向更新；文本可在云盘中打开阅读。\n" \
              "图片和附件保留原格式。这里不包含完整数据库，不能用于恢复 WAO 服务。\n\n" \
              + "\n".join(f"{name}：{count} 项" for name, count in counts.items()) + "\n"
    texts["开始阅读.txt"] = listing.encode("utf-8")
    new: dict[str, dict] = {}
    finished = 0
    progress_lock = threading.Lock()

    def progress() -> None:
        nonlocal finished
        with progress_lock:
            finished += 1
            if finished % 100 == 0:
                print(f"Readable content files processed: {finished}", flush=True)

    def put(path: str, sha: str, size: int, producer) -> int:
        new[path] = {"sha256": sha, "bytes": size}
        remote = ROOT + "/" + path
        previous = old.get(path)
        if previous == new[path]:
            progress()
            return 0
        if previous:
            actual = remote_bytes(client, remote)
            if digest(actual) != previous["sha256"]:
                raise RuntimeError("Cloud file was edited outside this exporter: " + path)
        else:
            existing_size = client.stat_size(remote, missing_ok=True)
            if resume_initial and path.startswith("图片/") and existing_size == size:
                # Image IDs are immutable; every previous PUT was size-checked.
                progress()
                return 0
            if existing_size is None:
                actual = None
            else:
                actual = remote_bytes(client, remote)
        if not previous and actual is not None:
            if digest(actual) == sha:
                progress()
                return 0
            if not resume_initial:
                raise RuntimeError("Cloud path already exists outside the manifest: " + path)
        data = producer()
        if len(data) != size or digest(data) != sha:
            raise RuntimeError("Content changed during export: " + path)
        upload_bytes(client, remote, data, ensured)
        progress()
        return 1

    jobs = [(path, digest(data), len(data), lambda data=data: data)
            for path, data in sorted(texts.items())]
    for table, kind, items in (("image_blob", "图片", image_meta),
                               ("file_blob", "附件", file_meta)):
        jobs.extend((media_path(kind, item), item["sha256"], item["bytes"],
                     lambda table=table, item=item: fetch_media(table, item)) for item in items)
    # 123 WebDAV may answer 423 when several workers MKCOL the same directory.
    for directory_name in sorted({ROOT + "/" + job[0].rsplit("/", 1)[0]
                                  for job in jobs if "/" in job[0]},
                                 key=lambda value: (value.count("/"), value)):
        client.ensure_dir(directory_name)
        ensured.add(directory_name)
    with ThreadPoolExecutor(max_workers=8) as pool:
        uploads = sum(pool.map(lambda job: put(*job), jobs))
    skipped = len(jobs) - uploads
    for path in {"开始阅读.txt", sorted(texts)[0], sorted(texts)[-1]}:
        if digest(remote_bytes(client, ROOT + "/" + path)) != digest(texts[path]):
            raise RuntimeError("Cloud text sample verification failed: " + path)
    for kind, items in (("图片", image_meta), ("附件", file_meta)):
        for item in (items[:1] + items[-1:]):
            path = media_path(kind, item)
            if digest(remote_bytes(client, ROOT + "/" + path)) != item["sha256"]:
                raise RuntimeError("Cloud media sample verification failed: " + path)

    stale = {path: old[path] for path in set(old) - set(new)}
    stale.update({path: meta for path, meta in pending.items() if path not in new})
    removed_count = len(stale)
    if len(stale) > max(20, len(old) // 5):
        raise RuntimeError("More than 20% of cloud files would be removed; refusing cleanup")
    manifest = {"formatVersion": 1, "scope": "all-knowledge-tasks",
                "generatedAt": datetime.now(timezone.utc).isoformat(),
                "counts": counts, "files": new, "pendingDelete": stale}

    def commit_manifest() -> None:
        encoded = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        upload_bytes(client, manifest_remote, encoded, ensured)
        if digest(remote_bytes(client, manifest_remote)) != digest(encoded):
            raise RuntimeError("Cloud manifest verification failed")

    commit_manifest()
    for path, meta in sorted(stale.items()):
        remote = ROOT + "/" + path
        if client.stat_size(remote, missing_ok=True) is not None:
            if digest(remote_bytes(client, remote)) != meta["sha256"]:
                raise RuntimeError("Stale cloud file was edited outside this exporter: " + path)
            client.delete_file(remote)
        del manifest["pendingDelete"][path]
    if removed_count:
        commit_manifest()
    return {"uploaded": uploads, "unchanged": skipped, "counts": counts,
            "removed": removed_count}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=Path("/etc/wao-backup/webdav.env"))
    parser.add_argument("--inventory", action="store_true", help="read source counts without uploading")
    parser.add_argument("--resume-initial", action="store_true",
                        help="overwrite stale files from an interrupted first upload")
    args = parser.parse_args()
    if args.inventory:
        texts, image_ids, file_ids, counts = wanted_content()
        images, missing_images = media_metadata("image_blob", image_ids)
        files, missing_files = media_metadata("file_blob", file_ids)
        counts["图片"] = len(images)
        counts["附件"] = len(files)
        counts["失效媒体引用"] = missing_images + missing_files
        print(json.dumps({"counts": counts, "textFiles": len(texts)}, ensure_ascii=False))
        return 0
    client, _ = build_client(args.config, False)
    outcome = sync(client, resume_initial=args.resume_initial)
    print(json.dumps(outcome, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as exc:
        print("Readable export failed: " + str(exc), file=sys.stderr)
        raise SystemExit(1)
