import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const pkg = JSON.parse(readFileSync(root + "package.json", "utf8"));
const version = process.env.MOBILE_VERSION || pkg.version;
const [major, minor, patch] = version.split(".").map(Number);
if (
  !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version) ||
  major > 2000 ||
  minor > 999 ||
  patch > 999
)
  throw Error("版本必须为 X.Y.Z，major <= 2000，minor/patch <= 999");
// Monotonic per SemVer. Never republish a released version; retry can reuse the same archive.
const code = major * 1_000_000 + minor * 1_000 + patch;
if (code < 1 || code > 2_100_000_000)
  throw Error("Android versionCode 超出范围");
const edit = (file, fn) =>
  writeFileSync(root + file, fn(readFileSync(root + file, "utf8")));
if (process.env.IOS_ONLY !== 'true') edit("android/app/build.gradle", (s) =>
  s
    .replace(/versionCode \d+/, `versionCode ${code}`)
    .replace(/versionName "[^"]+"/, `versionName "${version}"`),
);
edit("ios/App/App.xcodeproj/project.pbxproj", (s) =>
  s
    .replace(/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${version};`)
    .replace(
      /CURRENT_PROJECT_VERSION = [^;]+;/g,
      `CURRENT_PROJECT_VERSION = ${code};`,
    ),
);
console.log(`WAO ${version} (${code})`);
