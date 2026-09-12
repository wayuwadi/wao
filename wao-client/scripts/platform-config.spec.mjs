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

test("macOS icon override keeps a PNG for Tauri compile-time metadata and the dedicated ICNS bundle", () => {
  const config = JSON.parse(
    readFileSync(new URL("../src-tauri/tauri.macos.conf.json", import.meta.url)),
  );
  assert.deepEqual(config.bundle.icon, ["icons/icon-macos.png", "icons/icon-macos.icns"]);
});

test("JavaScript and Rust Tauri plugins keep matching major and minor versions", () => {
  const packageLock = JSON.parse(
    readFileSync(new URL("../package-lock.json", import.meta.url)),
  );
  const cargoLock = readFileSync(new URL("../src-tauri/Cargo.lock", import.meta.url), "utf8");
  for (const name of ["global-shortcut", "opener", "process", "updater"]) {
    const javascript = packageLock.packages[`node_modules/@tauri-apps/plugin-${name}`]?.version;
    const native = cargoLock.match(
      new RegExp(`name = "tauri-plugin-${name}"\\nversion = "(\\d+\\.\\d+)\\.`),
    )?.[1];
    assert(javascript && native, `${name}: both plugin locks must exist`);
    assert.equal(javascript.split(".").slice(0, 2).join("."), native, `${name}: plugin versions differ`);
  }
});
