import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
console.log("WAO 移动端环境检查（不读取或打印签名凭据）");
const check = (label, cmd, args, validate = () => true) => {
  const r = spawnSync(cmd, args, { encoding: "utf8" });
  const ok = r.status === 0 && validate(r.stdout + r.stderr);
  console.log(`${ok ? "✓" : "✗"} ${label}`);
  return ok;
};
check("Node 22.12+", "node", ["-v"], (s) => {
  const [a, b] = s.trim().slice(1).split(".").map(Number);
  return a > 22 || (a === 22 && b >= 12);
});
check(
  "JDK 21+",
  "java",
  ["-version"],
  (s) => Number(s.match(/version "(\d+)/)?.[1]) >= 21,
);
console.log(
  `${existsSync(process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || "") ? "✓" : "✗"} ANDROID_HOME / ANDROID_SDK_ROOT 指向 SDK`,
);
if (process.platform === "darwin")
  check(
    "完整 Xcode 26+（仅 CommandLineTools 不够）",
    "xcodebuild",
    ["-version"],
    (s) => Number(s.match(/Xcode (\d+)/)?.[1]) >= 26,
  );
console.log(
  "浏览器调试：npm run dev；真机：npm run android / npm run ios；云端构建见仓库 Actions / WAO Mobile Build。",
);
