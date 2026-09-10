import { readFileSync, appendFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
const [directory, version] = process.argv.slice(2);
const base = "https://wao.wayuwa.cn/downloads/desktop/client/";
const expected = JSON.parse(
  readFileSync(join(directory, "latest.json"), "utf8"),
);
const response = await fetch(base + "latest.json", { cache: "no-store" });
if (
  !response.ok ||
  !response.headers.get("cache-control")?.includes("no-store")
)
  throw new Error("Manifest unavailable or cached");
const actual = await response.json();
if (
  actual.version !== version ||
  JSON.stringify(actual.platforms) !== JSON.stringify(expected.platforms) ||
  JSON.stringify(actual.downloads) !== JSON.stringify(expected.downloads)
)
  throw new Error("Public manifest differs");
for (const line of readFileSync(
  join(directory, `SHA256-${version}.txt`),
  "utf8",
)
  .trim()
  .split("\n")) {
  const [hash, name] = line.split("  ");
  const file = await fetch(base + name);
  if (!file.ok) throw new Error(`Cannot download ${name}`);
  const data = Buffer.from(await file.arrayBuffer());
  if (createHash("sha256").update(data).digest("hex") !== hash)
    throw new Error(`Hash mismatch: ${name}`);
  console.log(`${name}: ${data.length} bytes, SHA256 verified`);
}
if (process.env.GITHUB_STEP_SUMMARY)
  appendFileSync(
    process.env.GITHUB_STEP_SUMMARY,
    `Windows x64 and macOS Universal ${version} published; all public artifact hashes verified.\n`,
  );
