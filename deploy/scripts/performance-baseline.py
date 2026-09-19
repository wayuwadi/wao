#!/usr/bin/env python3
"""显式启动只读性能基准，不把私有连接参数写入命令行或报告。"""
import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def main(argv=None):
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--env-file", help="显式指定已有私有连接文件，仅读取字面值")
    cli.add_argument("--java-home", help="已有 JDK 根目录")
    cli.add_argument("--maven-bin", default="mvn", help="已有 Maven 可执行程序")
    cli.add_argument("--doc-id", default="", help="可选的专用验收文档 ID；不提供则跳过文档场景")
    cli.add_argument("--samples", type=int, choices=range(3, 21), default=5)
    cli.add_argument("--warmups", type=int, choices=range(0, 6), default=2)
    options = cli.parse_args(argv)
    spec = importlib.util.spec_from_file_location("wao_dev_doctor", ROOT / "deploy/scripts/dev-doctor.py")
    doctor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(doctor)
    try:
        environment = dict(os.environ)
        environment.update(doctor.load_environment(options.env_file, environment))
        if any(not environment.get(key) for key in doctor.REQUIRED_DB):
            raise ValueError("缺少连接参数")
        if options.java_home:
            environment["JAVA_HOME"] = options.java_home
        environment["WAO_PERFORMANCE_BENCHMARK"] = "true"
        environment["WAO_PERFORMANCE_DOC_ID"] = options.doc_id
        environment["WAO_PERFORMANCE_SAMPLES"] = str(options.samples)
        environment["WAO_PERFORMANCE_WARMUPS"] = str(options.warmups)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, timeout=10).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True, timeout=10).strip())
        maven = shutil.which(options.maven_bin) if not Path(options.maven_bin).is_absolute() else options.maven_bin
        if not maven:
            raise ValueError("Maven 不存在")
        print("开始单并发只读基准；不启动 Spring，不执行迁移。数据库凭据仅通过子进程环境传递。", flush=True)
        command = [maven, "-B", "-pl", "wao-server", "-Dtest=PerformanceBaselineReadOnlyTest",
                   "-Dwao.benchmark.revision=" + revision,
                   "-Dwao.benchmark.workingTree=" + ("dirty" if dirty else "clean"), "test"]
        return subprocess.run(command, cwd=ROOT, env=environment, check=False).returncode
    except (OSError, ValueError, subprocess.SubprocessError):
        print("无法运行只读基准：请先用 dev-doctor.py 检查环境文件、工具路径和连接参数；未输出私有配置。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
