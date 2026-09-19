#!/usr/bin/env python3
"""只读检查 WAO 本地开发环境；诊断输出不得包含凭据或响应原文。"""
import argparse
import http.client
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

CONFIG_KEYS = {"MYSQL_HOST", "MYSQL_PORT", "DB_NAME", "DB_USER", "DB_PASS", "JAVA_HOME"}
REQUIRED_DB = ("MYSQL_HOST", "DB_NAME", "DB_USER", "DB_PASS")
ROOT = Path(__file__).resolve().parents[2]


def load_environment(path, environ):
    """只解析指定键的字面值，环境变量优先；永远不执行环境文件。"""
    values = {}
    if path:
        for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = re.match(r"^(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)$", stripped)
            if not match:
                raise ValueError(f"环境文件第 {number} 行不是 KEY=VALUE 格式")
            key, raw = match.groups()
            if key not in CONFIG_KEYS:
                continue
            try:
                parts = shlex.split(raw, comments=True, posix=True)
            except ValueError:
                raise ValueError(f"环境文件第 {number} 行引号未闭合") from None
            if len(parts) > 1:
                raise ValueError(f"环境文件第 {number} 行含空格的值需要引号")
            values[key] = parts[0] if parts else ""
    values.update({key: value for key, value in environ.items() if key in CONFIG_KEYS})
    return values


def version_tuple(text):
    match = re.search(r"(?<!\d)(\d+)\.(\d+)(?:\.(\d+))?", text)
    if not match:
        return None
    return tuple(int(value or 0) for value in match.groups())


def supports_node(version, expression):
    """识别仓库使用的 ^x.y.z / >=x.y.z 范围；未知语法不猜测。"""
    ranges = []
    for part in expression.split("||"):
        match = re.fullmatch(r"\s*(\^|>=)(\d+)\.(\d+)\.(\d+)\s*", part)
        if not match:
            raise ValueError("Node 版本范围语法需更新体检工具")
        operator = match[1]
        minimum = tuple(int(value) for value in match.groups()[1:])
        ranges.append(version >= minimum and (operator == ">=" or version[0] == minimum[0]))
    return any(ranges)


def local_origin(value):
    parsed = urllib.parse.urlsplit(value)
    try:
        parsed.port
    except ValueError:
        raise ValueError("本地服务端口无效") from None
    if (parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment):
        raise ValueError("服务地址只允许无凭据、无路径的本地 HTTP 源")
    return value.rstrip("/")


def origin_argument(value):
    try:
        return local_origin(value)
    except ValueError:
        # argparse 的默认 ValueError 处理会回显原值，不能让带密码的 URL 出现在错误输出。
        raise argparse.ArgumentTypeError("只允许无凭据、无路径的本地 HTTP 源地址") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(url, timeout):
    # 不使用用户代理、不跟随跳转，也不读取或发送浏览器 Cookie。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, headers={"Accept": "application/json, text/html"}), timeout=timeout) as response:
            return response.status, response.headers.get("Content-Type", ""), response.read(65_537)
    except urllib.error.HTTPError as error:
        return error.code, "", b""


class Doctor:
    def __init__(self, args, environ=None):
        self.args = args
        self.environment = dict(os.environ if environ is None else environ)
        self.checks = []

    def add(self, identifier, status, message, action=""):
        self.checks.append({"id": identifier, "status": status, "message": message, "action": action})

    def executable(self, requested, name):
        return requested or shutil.which(name, path=self.environment.get("PATH", ""))

    def tool_version(self, identifier, executable, flag, pattern, predicate, action):
        if not executable:
            self.add(identifier, "fail", "未找到可执行程序", action)
            return
        try:
            result = subprocess.run([str(executable), flag], capture_output=True, text=True,
                                    timeout=self.args.timeout, env=self.environment, check=False)
            match = re.search(pattern, result.stdout + result.stderr)
            version = version_tuple(match[0]) if match else None
            if result.returncode != 0 or version is None:
                self.add(identifier, "fail", "程序未正常返回可识别版本", action)
                return
            ok = predicate(version)
            self.add(identifier, "pass" if ok else "fail", "版本 " + ".".join(map(str, version)), "" if ok else action)
        except subprocess.TimeoutExpired:
            self.add(identifier, "fail", "版本检查超时", action)
        except FileNotFoundError:
            self.add(identifier, "fail", "可执行文件路径不存在", action)
        except PermissionError:
            self.add(identifier, "fail", "程序没有执行权限", action)
        except OSError as error:
            message = "可执行文件与当前 CPU 架构或系统不兼容" if error.errno in {8, 86} else "程序无法执行"
            self.add(identifier, "fail", message, action)
        except ValueError:
            self.add(identifier, "fail", "版本范围不受支持", action)

    def runtimes(self, config):
        try:
            pom = ET.parse(ROOT / "pom.xml")
            java_min = int(pom.findtext("{*}properties/{*}java.version"))
            package = json.loads((ROOT / "wao-web/package.json").read_text())
            engines = package["engines"]["node"]
        except (OSError, ValueError, KeyError, TypeError, ET.ParseError):
            self.add("repository", "fail", "无法读取仓库运行时基线", "在完整 WAO checkout 内运行体检")
            return
        java_home = self.args.java_home or config.get("JAVA_HOME")
        if java_home:
            self.environment["JAVA_HOME"] = java_home
        java = str(Path(java_home) / "bin/java") if java_home else self.executable(None, "java")
        self.tool_version("java", java, "-version", r'version\s+"\d+\.\d+[^"\n]*"',
                          lambda v: v[0] >= java_min, f"配置 JDK {java_min}+ 的 JAVA_HOME 或 --java-home；优先复用 Java 17 基线")
        self.tool_version("maven", self.executable(self.args.maven_bin, "mvn"), "--version", r"Apache Maven \d+\.\d+\.\d+",
                          lambda v: v >= (3, 9, 0), "将 Maven 3.9+ 加入 PATH，或指定 --maven-bin，并确认 JAVA_HOME")
        self.tool_version("node", self.executable(self.args.node_bin, "node"), "--version", r"v\d+\.\d+\.\d+",
                          lambda v: supports_node(v, engines), f"配置满足 {engines} 的 Node，或指定 --node-bin")
        installed = ROOT / "wao-web/node_modules/vite/package.json"
        if installed.is_file():
            self.add("frontend-dependencies", "pass", "已找到前端 Vite 依赖（未执行构建）")
        else:
            self.add("frontend-dependencies", "fail", "尚未安装前端依赖", "在 wao-web 执行 npm ci；独立 worktree 需有自己的依赖入口")

    def database(self, config):
        missing = [key for key in REQUIRED_DB if not config.get(key) or re.fullmatch(r"<.*>", config[key])]
        if missing:
            self.add("database-config", "fail", "缺少有效变量：" + "、".join(missing),
                     "提供既有本地连接环境，或通过 --env-file 指定私有环境文件；不要使用示例占位值")
            self.add("database-tcp", "skip", "连接参数不完整，未访问数据库")
            return
        self.add("database-config", "pass", "必需连接变量齐全（不输出变量值）")
        try:
            port = int(config.get("MYSQL_PORT") or "3306")
            if not 1 <= port <= 65535:
                raise ValueError()
        except ValueError:
            self.add("database-tcp", "fail", "MYSQL_PORT 无效", "使用 1—65535 的端口号")
            return
        if self.args.offline:
            self.add("database-tcp", "skip", "离线模式未检查数据库端口")
            return
        try:
            with socket.create_connection((config["MYSQL_HOST"], port), timeout=self.args.timeout):
                self.add("database-tcp", "pass", "数据库 TCP 端口可连接（未验证账号、SQL 或数据库身份）")
        except (OSError, ValueError):
            self.add("database-tcp", "fail", "数据库 TCP 连接失败或超时",
                     "检查既有 SSH 隧道是否运行及 MYSQL_HOST/MYSQL_PORT 是否匹配；本工具不会重启隧道")

    def services(self):
        for identifier, base, path in (("api-health", self.args.api_url, "/actuator/health"),
                                       ("web-page", self.args.web_url, "/")):
            if not base:
                self.add(identifier, "skip", "未指定检查地址")
                continue
            if self.args.offline:
                self.add(identifier, "skip", "离线模式未访问本地服务")
                continue
            action = ("检查本地 local-cloud 服务启动日志及数据库连接；确认 --api-url 指向业务服务"
                      if identifier == "api-health" else "启动本地 Vite，确认 --web-url 端口以及代理的业务服务地址")
            try:
                status, media_type, body = http_get(base + path, self.args.timeout)
                if status != 200:
                    self.add(identifier, "fail", f"HTTP {status}，未得到预期服务响应", action)
                    continue
                if identifier == "api-health":
                    healthy = "json" in media_type and json.loads(body).get("status") == "UP"
                    if healthy:
                        hello_status, hello_type, hello_body = http_get(base + "/api/hello", self.args.timeout)
                        hello = json.loads(hello_body) if "json" in hello_type else {}
                        healthy = (hello_status == 200 and hello.get("code") == 0
                                   and hello.get("data", {}).get("service") == "wao-server")
                else:
                    healthy = "text/html" in media_type and re.search(rb"<title[^>]*>\s*WAO\b", body, re.I) is not None
                self.add(identifier, "pass" if healthy else "fail",
                         ("业务服务健康状态为 UP（未验证用户链路）" if identifier == "api-health" else "WAO 页面入口可读取（未执行浏览器交互）")
                         if healthy else "端口响应不符合预期服务格式", "" if healthy else action)
            except (OSError, ValueError, AttributeError, http.client.HTTPException):
                self.add(identifier, "fail", "请求失败、超时或响应格式无效", action)

    def run(self):
        try:
            config = load_environment(self.args.env_file, self.environment)
            self.add("environment", "pass", "已读取显式环境文件和进程变量" if self.args.env_file else "仅使用当前进程环境")
        except (OSError, ValueError, UnicodeError):
            config = {key: value for key, value in self.environment.items() if key in CONFIG_KEYS}
            self.add("environment", "fail", "指定环境文件不可读取或格式无效", "检查文件权限、KEY=VALUE 格式及引号；文件内容不会被执行或回显")
        self.runtimes(config)
        self.database(config)
        self.services()
        failures = sum(row["status"] == "fail" for row in self.checks)
        return {"scope": "offline" if self.args.offline else "local-development", "checks": self.checks,
                "summary": {state: sum(row["status"] == state for row in self.checks) for state in ("pass", "fail", "skip")},
                "exitCode": 1 if failures else 0,
                "note": "体检只读；不代表真实用户链路验收通过。"}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--env-file", help="显式指定私有环境文件，不执行文件内容")
    result.add_argument("--java-home", help="已安装 JDK 根目录")
    result.add_argument("--maven-bin", help="已安装 mvn 可执行文件")
    result.add_argument("--node-bin", help="已安装 node 可执行文件")
    result.add_argument("--api-url", type=origin_argument, default="http://127.0.0.1:18080")
    result.add_argument("--web-url", type=origin_argument, help="可选本地前端源，例如 http://127.0.0.1:5174")
    result.add_argument("--timeout", type=float, default=4, help="单项超时秒数，默认 4，范围 0.1—15")
    result.add_argument("--offline", action="store_true", help="跳过全部网络检查")
    result.add_argument("--json", action="store_true", help="输出机器可读报告")
    return result


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    if not 0.1 <= args.timeout <= 15:
        cli.error("--timeout 必须在 0.1—15 秒之间")
    report = Doctor(args).run()
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        labels = {"pass": "通过", "fail": "失败", "skip": "跳过"}
        for row in report["checks"]:
            print(f'[{labels[row["status"]]}] {row["id"]}：{row["message"]}')
            if row["action"]:
                print("  下一步：" + row["action"])
        summary = report["summary"]
        print(f'通过 {summary["pass"]}；失败 {summary["fail"]}；跳过 {summary["skip"]}。{report["note"]}')
    return report["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
