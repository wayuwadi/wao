import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
const version = process.argv[2];
if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version || ""))
  throw Error("用法：npm run release -- 1.0.1（先合并功能 PR，再运行此命令）");
function run(cmd, args, capture = false) {
  const r = spawnSync(cmd, args, {
    encoding: "utf8",
    stdio: capture ? "pipe" : "inherit",
  });
  if (r.status !== 0) throw Error(`${cmd} 执行失败`);
  return r.stdout?.trim();
}
if (run("git", ["status", "--porcelain"], true))
  throw Error("工作区不干净，请先提交并合并功能 PR");
run("git", ["fetch", "origin", "main", "--tags"]);
if (
  run("git", ["rev-parse", "HEAD"], true) !==
  run("git", ["rev-parse", "origin/main"], true)
)
  throw Error("请在最新 main 上发布；标签必须指向已合并代码");
const last = run(
  "git",
  ["tag", "--list", "mobile-v*", "--sort=-version:refname"],
  true,
)?.split("\n")[0];
const tuple = (v) =>
  v
    .replace(/^mobile-v/, "")
    .split(".")
    .map(Number);
if (last) {
  const a = tuple(version),
    b = tuple(last);
  const cmp = a.findIndex((x, i) => x !== b[i]);
  if (cmp < 0 || a[cmp] < b[cmp]) throw Error(`必须高于已发布版本 ${last}`);
}
run("npm", ["run", "check"]);
run("git", ["tag", "-a", `mobile-v${version}`, "-m", `WAO 移动端 ${version}`]);
run("git", ["push", "origin", `mobile-v${version}`]);
console.log(
  `已触发移动端 ${version} 构建。查看仓库 Actions → WAO Mobile Release。`,
);
