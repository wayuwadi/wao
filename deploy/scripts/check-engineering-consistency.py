#!/usr/bin/env python3
"""只读检查本次交付的编号、migration 不可变性和 Markdown 本地路径。"""
import argparse
from collections import defaultdict
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
ADR = "docs/adr"
MIGRATIONS = "wao-server/src/main/resources/db/migration"
ADR_NAME = re.compile(r"(\d+)-[^/]+\.md$")
MIGRATION_NAME = re.compile(r"V(\d+)__[A-Za-z0-9_]+\.sql$")


class CheckUnavailable(Exception):
    pass


def git(root, *args, allowed=(0,)):
    try:
        result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        raise CheckUnavailable("无法运行 Git；请检查仓库位置和工具环境。") from None
    if result.returncode not in allowed:
        raise CheckUnavailable("Git 查询失败；请检查基线引用是否存在。未输出仓库内容。")
    return result


def paths(raw):
    return {item.decode("utf-8") for item in raw.split(b"\0") if item}


def in_directory(path, directory):
    return str(PurePosixPath(path).parent) == directory


def safe_file(root, path):
    candidate = root / path
    try:
        candidate.resolve().relative_to(root.resolve())
    except ValueError:
        raise CheckUnavailable("检查范围中的文件指向仓库外；请先核对符号链接。") from None
    return candidate


def issue(issues, severity, code, message, files=()):
    issues.append({"severity": severity, "code": code, "message": message, "files": sorted(files)})


def numbered(files, directory, pattern):
    result = defaultdict(set)
    for path in files:
        if in_directory(path, directory):
            match = pattern.fullmatch(PurePosixPath(path).name)
            if match:
                result[int(match.group(1))].add(path)
    return result


def markdown_targets(text):
    """支持常见 Markdown 路径；不把代码示例或 URL 当成仓库文件。"""
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            run = marker.group(1)
            if fence is None:
                fence = run
            elif run[0] == fence[0] and len(run) >= len(fence):
                fence = None
            lines.append("")
        else:
            lines.append("" if fence else re.sub(r"(`+).*?\1", "", line))
    clean = "\n".join(lines)
    # 内联目标支持尖括号包裹的空格、转义字符，以及一层成对括号。
    destination = r"(<[^>\n]+>|(?:\\.|[^\s()<>]|\([^()\n]*\))+)"
    inline = re.compile(r"!?\[[^\]\n]*\]\(\s*" + destination + r"(?:\s+[\"'][^\n]*?[\"'])?\s*\)")
    reference = re.compile(r"^\s{0,3}\[[^\]\n]+\]:\s*" + destination, re.MULTILINE)
    return [match.group(1).strip("<>") for regex in (inline, reference) for match in regex.finditer(clean)]


def check(root, base_ref):
    root = root.resolve()
    sha = git(root, "rev-parse", "--verify", "--end-of-options", base_ref + "^{commit}").stdout.decode().strip()
    ancestor = git(root, "merge-base", "--is-ancestor", sha, "HEAD", allowed=(0, 1))
    result = {"base": sha, "issues": [], "counts": {"adr": 0, "migrations": 0, "changedMarkdown": 0}}
    issues = result["issues"]
    if ancestor.returncode:
        issue(issues, "error", "BASE_NOT_INCLUDED", "当前 HEAD 尚未包含基线；先同步或 rebase 后重跑，不能跳过远端编号核对。")
        return result
    base_files = paths(git(root, "ls-tree", "-rz", "--name-only", sha).stdout)
    candidates = paths(git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").stdout)
    changed = paths(git(root, "diff", "--name-only", "-z", sha, "--").stdout)
    changed.update(candidates - base_files)
    current_files = {path for path in candidates if (root / path).is_file()}
    for directory, pattern, label in ((ADR, ADR_NAME, "ADR"), (MIGRATIONS, MIGRATION_NAME, "MIGRATION")):
        relevant = {path for path in current_files if in_directory(path, directory)
                    and path.endswith(".md" if label == "ADR" else ".sql")}
        result["counts"]["adr" if label == "ADR" else "migrations"] = len(relevant)
        for path in relevant:
            safe_file(root, path)
            # ADR 目录允许 README 索引，SQL 则必须全部遵守项目版本命名规则。
            if PurePosixPath(path).name == "README.md" and label == "ADR":
                continue
            if not pattern.fullmatch(PurePosixPath(path).name):
                issue(issues, "error", label + "_NAME", "文件名不符合项目编号约定。", [path])
        before = numbered(base_files, directory, pattern)
        for version, group in sorted(numbered(relevant, directory, pattern).items()):
            if len(group) > 1:
                historical = label == "ADR" and group.issubset(before.get(version, set()))
                issue(issues, "warning" if historical else "error", label + "_DUPLICATE",
                      f"{'基线已有' if historical else '存在'}重复编号 {version}；"
                      + ("历史 ADR 引用需要单独治理。" if historical else "请为本次新增文件使用未占用编号。"), group)
    for path in sorted(base_files):
        if in_directory(path, MIGRATIONS) and path.endswith(".sql"):
            if path not in current_files:
                issue(issues, "error", "MIGRATION_REMOVED", "基线 migration 被删除或改名；必须保留并新增版本。", [path])
            elif safe_file(root, path).read_bytes() != git(root, "show", sha + ":" + path).stdout:
                issue(issues, "error", "MIGRATION_MODIFIED", "基线 migration 内容已变化；应恢复原文件并新增版本。", [path])
    for path in sorted(current_files):
        if path not in changed or not path.lower().endswith(".md") or not (path.startswith("docs/") or path in {"README.md", "AGENTS.md"}):
            continue
        content = safe_file(root, path).read_bytes()
        result["counts"]["changedMarkdown"] += 1
        for target in markdown_targets(content.decode("utf-8")):
            target = re.sub(r"\\([\\ ()])", r"\1", target)
            url = urlsplit(target)
            if url.scheme or url.netloc or not url.path:
                continue
            decoded = unquote(url.path)
            destination = (root / decoded.lstrip("/")) if decoded.startswith("/") else (root / path).parent / decoded
            try:
                destination.resolve().relative_to(root)
            except ValueError:
                issue(issues, "error", "LINK_OUTSIDE_REPOSITORY", "本地链接离开仓库，无法作为可移植文档引用。", [path])
                continue
            if not destination.exists():
                issue(issues, "error", "LINK_MISSING", "本地链接目标不存在：" + target, [path])
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--base", default="origin/main", help="已在本地更新的基线引用；不会自动 fetch")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = check(args.root, args.base)
    except (CheckUnavailable, OSError, UnicodeError, ValueError):
        print("无法完成检查：请确认 Git 基线、仓库内文件编码和符号链接；未读取私有环境配置。", file=sys.stderr)
        return 2
    errors = sum(item["severity"] == "error" for item in result["issues"])
    result["status"] = "failed" if errors else "passed"
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("本地基线：" + result["base"] + "（请在检查前自行 fetch 最新 main）")
        print("检查数量：" + json.dumps(result["counts"], ensure_ascii=False))
        for item in result["issues"]:
            print(f'{item["severity"]} [{item["code"]}] {item["message"]}')
            for path in item["files"]:
                print("  " + path)
        print(f"结果：{errors} 个错误，{len(result['issues']) - errors} 个历史警告")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
