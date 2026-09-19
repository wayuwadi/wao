#!/usr/bin/env python3
"""预检报告登记的近期验收文档；--apply 才通过既有逻辑移入回收站。"""
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
    cli.add_argument("report", type=Path, help="仓库外 report.json 的实际路径")
    cli.add_argument("--apply", action="store_true", help="对全部核对通过的精确文档执行可恢复软删除")
    cli.add_argument("--env-file")
    cli.add_argument("--java-home")
    cli.add_argument("--maven-bin", default="mvn")
    args = cli.parse_args(argv)
    spec = importlib.util.spec_from_file_location("wao_doctor", ROOT / "deploy/scripts/dev-doctor.py")
    doctor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(doctor)
    try:
        report = args.report.resolve(strict=True)
        if ROOT == report or ROOT in report.parents:
            raise ValueError("报告必须在仓库外")
        environment = dict(os.environ)
        environment.update(doctor.load_environment(args.env_file, environment))
        if any(not environment.get(key) for key in doctor.REQUIRED_DB):
            raise ValueError("缺少连接参数")
        if args.java_home:
            environment["JAVA_HOME"] = args.java_home
        environment.update(WAO_DRAFT_CLEANUP="true", WAO_DRAFT_CLEANUP_REPORT=str(report),
                           WAO_DRAFT_CLEANUP_APPLY="true" if args.apply else "false")
        maven = args.maven_bin if Path(args.maven_bin).is_absolute() else shutil.which(args.maven_bin)
        if not maven:
            raise ValueError("Maven 不可用")
        print("模式：" + ("核对全部目标后软删除，保留回收站恢复能力" if args.apply else "只读预检，不执行清理"), flush=True)
        return subprocess.run([maven, "-B", "-pl", "wao-server", "-Dtest=DraftAcceptanceCleanupTest",
                               "-Duser.timezone=Asia/Shanghai", "test"], cwd=ROOT, env=environment, check=False).returncode
    except (OSError, ValueError, subprocess.SubprocessError):
        print("未完成清理：请核对报告路径、已有工具与私有环境配置。未输出配置内容。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
