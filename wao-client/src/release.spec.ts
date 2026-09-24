import { describe, expect, it } from "vitest";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

describe("release artifacts", () => {
  it("requires a signature before creating a manifest and keeps versioned URLs", () => {
    const directory = mkdtempSync(join(tmpdir(), "wao-client-test-"));
    const raw = join(directory, "raw"),
      output = join(directory, "out");
    mkdirSync(raw);
    const installer = join(raw, "WAO_1.0.0_x64-setup.exe");
    writeFileSync(installer, "installer fixture, not an executable");
    const dmg = join(raw, "WAO_1.0.0_universal.dmg");
    const update = join(raw, "WAO.app.tar.gz");
    writeFileSync(dmg, "dmg fixture");
    writeFileSync(update, "universal update fixture");
    const args = [resolve("scripts/prepare-release.mjs"), raw, output, "1.0.0"];
    try {
      expect(spawnSync(process.execPath, args).status).not.toBe(0);
      writeFileSync(`${installer}.sig`, "fixture-signature-not-for-production");
      expect(spawnSync(process.execPath, args).status).not.toBe(0);
      writeFileSync(
        `${update}.sig`,
        "macos-fixture-signature-not-for-production",
      );
      expect(spawnSync(process.execPath, args).status).toBe(0);
      const manifest = JSON.parse(
        readFileSync(join(output, "latest.json"), "utf8"),
      );
      expect(manifest.notes).toContain("系统级快捷键");
      expect(manifest.platforms["windows-x86_64"].url).toBe(
        "https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.0_windows-x64-setup.exe",
      );
      expect(Object.keys(manifest.platforms)).toEqual([
        "windows-x86_64",
        "darwin-x86_64",
        "darwin-aarch64",
      ]);
      expect(manifest.platforms["darwin-x86_64"]).toEqual(
        manifest.platforms["darwin-aarch64"],
      );
      expect(manifest.platforms["darwin-aarch64"].url).toMatch(
        /macos-universal\.app\.tar\.gz$/,
      );
      expect(manifest.downloads.macos.url).toMatch(/macos-universal\.dmg$/);
      expect(manifest.downloads.macos.sha256).toMatch(/^[a-f0-9]{64}$/);
      expect(manifest.downloads.macos.size).toBe(11);
      expect(
        readFileSync(join(output, "WAO-macos-universal.dmg"), "utf8"),
      ).toBe("dmg fixture");
      expect(readFileSync(join(output, "SHA256-1.0.0.txt"), "utf8")).toMatch(
        /^[a-f0-9]{64}  WAO_1.0.0_windows-x64-setup.exe/,
      );
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
  it("does not grant remote content updater or shell permissions", () => {
    const capability = JSON.parse(
      readFileSync(resolve("src-tauri/capabilities/shell.json"), "utf8"),
    );
    expect(capability.webviews).toEqual(["main"]);
    expect(capability.remote).toBeUndefined();
    expect(capability.windows).toBeUndefined();
  });
  it("places Mac workspace controls in the overlaid window titlebar", () => {
    const config = JSON.parse(
      readFileSync(resolve("src-tauri/tauri.macos.conf.json"), "utf8"),
    );
    const main = config.app.windows.find((window: { label: string }) => window.label === "main");
    expect(main).toMatchObject({ decorations: true, transparent: true, titleBarStyle: "Overlay", hiddenTitle: true });
  });
});
