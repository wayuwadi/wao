import { test } from "vitest";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

test("macOS transparent-window feature matches Tauri config on every build platform", () => {
  const base = JSON.parse(readFileSync(new URL("../src-tauri/tauri.conf.json", import.meta.url)));
  const cargo = readFileSync(new URL("../src-tauri/Cargo.toml", import.meta.url), "utf8");
  const features = cargo.match(/^tauri\s*=\s*\{[^\n]*features\s*=\s*\[([^\]]*)\]/m)?.[1];
  assert(features, "Tauri dependency must declare its native features");
  const privateApi = features.includes('"macos-private-api"');
  for (const platform of ["windows", "macos"]) {
    const override = JSON.parse(readFileSync(new URL(`../src-tauri/tauri.${platform}.conf.json`, import.meta.url)));
    const app = { ...base.app, ...override.app };
    assert.equal(privateApi, app.macOSPrivateApi === true, `${platform}: feature/config mismatch breaks cargo test before compilation`);
  }
});
