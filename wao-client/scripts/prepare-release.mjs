import {
  readdirSync,
  readFileSync,
  mkdirSync,
  copyFileSync,
  writeFileSync,
  statSync,
} from "node:fs";
import { resolve, join } from "node:path";
import { createHash } from "node:crypto";

const [raw, output, version] = process.argv.slice(2);
if (
  !raw ||
  !output ||
  !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version ?? "")
)
  throw new Error(
    "Expected raw directory, output directory and stable version",
  );
function files(path) {
  return readdirSync(path, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? files(join(path, entry.name))
      : [join(path, entry.name)],
  );
}
const all = files(resolve(raw));
function one(suffix) {
  const matches = all.filter((path) => path.endsWith(suffix));
  if (matches.length !== 1)
    throw new Error(`Expected one ${suffix}, found ${matches.length}`);
  if (!statSync(matches[0]).size) throw new Error(`Empty artifact: ${suffix}`);
  return matches[0];
}
function signed(path) {
  const signature = readFileSync(`${path}.sig`, "utf8").trim();
  if (signature.length < 20) throw new Error("Missing updater signature");
  return signature;
}
const windows = one("-setup.exe"),
  dmg = one(".dmg"),
  macos = one(".app.tar.gz");
const windowsSignature = signed(windows),
  macosSignature = signed(macos);
const base = "https://wao.wayuwa.cn/downloads/desktop/client/";
const windowsName = `WAO_${version}_windows-x64-setup.exe`;
const macosName = `WAO_${version}_macos-universal.app.tar.gz`;
const dmgName = `WAO_${version}_macos-universal.dmg`;
mkdirSync(output, { recursive: true });
const checksums = [];
function artifact(source, name) {
  copyFileSync(source, join(output, name));
  const sha256 = createHash("sha256")
    .update(readFileSync(source))
    .digest("hex");
  checksums.push(`${sha256}  ${name}`);
  return { url: base + name, size: statSync(source).size, sha256 };
}
const windowsDownload = artifact(windows, windowsName);
artifact(`${windows}.sig`, `${windowsName}.sig`);
artifact(windows, "WAO-windows-x64-setup.exe");
const macosDownload = artifact(dmg, dmgName);
artifact(dmg, "WAO-macos-universal.dmg");
artifact(macos, macosName);
artifact(`${macos}.sig`, `${macosName}.sig`);
writeFileSync(
  join(output, `SHA256-${version}.txt`),
  checksums.join("\n") + "\n",
);
writeFileSync(
  join(output, "latest.json"),
  JSON.stringify(
    {
      version,
      notes: `WAO ${version}：新增独立桌面宠物：消息气泡、引用快速回复、团队聊天与独立 AI 对话；主窗口收起后宠物仍可使用。`,
      pub_date: new Date().toISOString(),
      platforms: {
        "windows-x86_64": {
          signature: windowsSignature,
          url: base + windowsName,
        },
        "darwin-x86_64": { signature: macosSignature, url: base + macosName },
        "darwin-aarch64": { signature: macosSignature, url: base + macosName },
      },
      downloads: { windows: windowsDownload, macos: macosDownload },
    },
    null,
    2,
  ) + "\n",
);
