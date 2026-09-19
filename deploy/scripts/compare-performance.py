#!/usr/bin/env python3
"""比较两份只读性能报告；不把跨快照差异自动判定为代码收益。"""
import argparse
import json
import math
import re
from pathlib import Path
import statistics
import sys


def load_report(path):
    result = json.loads(Path(path).read_text(encoding="utf-8"))
    if result.get("schemaVersion") != 1 or result.get("measurement") != "local-service-and-database-roundtrip":
        raise ValueError("报告版本或测量范围不匹配")
    if not isinstance(result.get("scenarios"), list) or not result["scenarios"]:
        raise ValueError("报告没有场景")
    ids = set()
    for scenario in result["scenarios"]:
        identifier = scenario.get("id")
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-z0-9-]{1,64}", identifier) or identifier in ids:
            raise ValueError("场景名称缺失或重复")
        ids.add(identifier)
        samples = scenario.get("samples")
        if not isinstance(samples, list) or len(samples) < 3:
            raise ValueError("每个场景至少需要三个样本")
        for sample in samples:
            for key in ("elapsedMs", "queries", "returnedRows", "resultBytes"):
                value = sample.get(key)
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                    raise ValueError("样本指标无效")
        # 从原始样本重算，不能只信报告里的摘要中位数。
        scenario["medianMs"] = statistics.median(sample["elapsedMs"] for sample in samples)
    return result


def compare(before, after):
    warnings = ["两份报告来自独立快照；行数相同也不证明数据、负载或网络条件相同。耗时变化仅供复测定位。"]
    for key, label in (("warmups", "预热次数"), ("repetitions", "样本数"), ("javaVersion", "Java 版本"), ("dataShape", "数据概况")):
        if before.get(key) != after.get(key):
            warnings.append(label + "不同，请在相同条件下复测。")
    if any(report.get("workingTree") != "clean" for report in (before, after)):
        warnings.append("至少一份报告来自未提交或状态未知的工作区，Git SHA 不能完整描述被测代码。")
    previous = {row["id"]: row for row in before["scenarios"]}
    current = {row["id"]: row for row in after["scenarios"]}
    if set(previous) != set(current):
        warnings.append("场景集合不同；只比较共有场景，并单独列出缺失项。")
    rows = []
    for identifier in sorted(previous.keys() & current.keys()):
        first, second = previous[identifier], current[identifier]
        old_ms, new_ms = first["medianMs"], second["medianMs"]
        if not first.get("outputsStable") or not second.get("outputsStable"):
            warnings.append(identifier + "同轮输出发生变化，需先核对结果语义。")
        row = {"scenario": identifier, "beforeMs": old_ms, "afterMs": new_ms,
               "changePercent": (new_ms / old_ms - 1) * 100 if old_ms else None}
        for key in ("queries", "returnedRows", "resultBytes"):
            row[key] = {"before": statistics.median(sample[key] for sample in first["samples"]),
                        "after": statistics.median(sample[key] for sample in second["samples"])}
        rows.append(row)
    return {"warnings": warnings, "rows": rows, "onlyBefore": sorted(previous.keys() - current.keys()),
            "onlyAfter": sorted(current.keys() - previous.keys())}


def main(argv=None):
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("before")
    cli.add_argument("after")
    cli.add_argument("--json", action="store_true")
    args = cli.parse_args(argv)
    try:
        result = compare(load_report(args.before), load_report(args.after))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        print("报告不存在、格式无效或缺少可比较样本；未输出报告原文。", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        for warning in result["warnings"]:
            print("注意：" + warning)
        print("\n| 场景 | 中位耗时 ms（前 → 后） | 耗时变化 | 查询次数 | 返回行数 | DTO 字节数 |")
        print("| --- | ---: | ---: | ---: | ---: | ---: |")
        for row in result["rows"]:
            change = f'{row["changePercent"]:+.1f}%' if row["changePercent"] is not None else "基线为零"
            metrics = [f'{row[key]["before"]:g} → {row[key]["after"]:g}' for key in ("queries", "returnedRows", "resultBytes")]
            print(f'| {row["scenario"]} | {row["beforeMs"]:.2f} → {row["afterMs"]:.2f} | {change} | ' + " | ".join(metrics) + " |")
        if result["onlyBefore"]:
            print("仅前一份包含：" + "、".join(result["onlyBefore"]))
        if result["onlyAfter"]:
            print("仅后一份包含：" + "、".join(result["onlyAfter"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
