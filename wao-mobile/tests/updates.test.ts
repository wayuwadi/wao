import { beforeEach, expect, it, vi } from "vitest";
import {
  newer,
  parseRelease,
  downloadUrl,
  type MobileRelease,
} from "../src/api/updates";
import { createUpdateStore } from "../src/composables/updates";
const release = (version = "1.2.0"): MobileRelease => ({
  version,
  publishedAt: "2026-09-10T12:00:00Z",
  files: {
    android: {
      name: `WAO-${version}-android.apk`,
      size: 1024,
      sha256: "a".repeat(64),
    },
    ios: {
      name: `WAO-${version}-ios-unsigned.ipa`,
      size: 1024,
      sha256: "b".repeat(64),
    },
  },
});
const setup = () => ({
  fetch: vi.fn(async () => release()),
  installed: vi.fn(async () => "1.1.1"),
  open: vi.fn(async (_url: string) => {}),
  platform: () => "android",
  now: vi.fn(() => 1000000000),
  storage: localStorage,
});
beforeEach(() => localStorage.clear());
it("compares versions numerically and refuses invalid versions", () => {
  expect(newer("1.10.0", "1.9.9")).toBe(true);
  expect(newer("1.1.1", "1.1.1")).toBe(false);
  expect(newer("1.1.0", "1.1.1")).toBe(false);
  expect(() => newer("1.01.0", "1.0.0")).toThrow();
  expect(() => newer("1.2.0-beta", "1.0.0")).toThrow();
});
it("rejects untrusted or malformed package names and checksums", () => {
  const r = release();
  expect(parseRelease(r)).toEqual(r);
  expect(downloadUrl(r, "ios")).toBe(
    "https://wao.wayuwa.cn/downloads/mobile/1.2.0/WAO-1.2.0-ios-unsigned.ipa",
  );
  for (const name of [
    "../../evil.apk",
    "https://evil.example/app.apk",
    "WAO-1.1.0-android.apk",
  ])
    expect(() =>
      parseRelease({ ...r, files: { android: { ...r.files.android!, name } } }),
    ).toThrow();
  expect(() =>
    parseRelease({
      ...r,
      files: { android: { ...r.files.android!, sha256: "bad" } },
    }),
  ).toThrow();
});
it("prompts on a newer platform build and manual check bypasses a 24 hour snooze", async () => {
  const deps = setup(),
    u = createUpdateStore(deps);
  await u.initialize();
  expect(u.version.value).toBe("1.1.1");
  expect(u.modal.value).toBe(true);
  u.later();
  expect(u.modal.value).toBe(false);
  const restart = createUpdateStore(deps);
  await restart.initialize();
  expect(restart.modal.value).toBe(false);
  expect(restart.available.value).toBe(true);
  await restart.check(true);
  expect(restart.modal.value).toBe(true);
});
it("a different newer version is not suppressed by a snooze", async () => {
  const deps = setup(),
    u = createUpdateStore(deps);
  await u.initialize();
  u.later();
  deps.fetch.mockResolvedValue(release("1.3.0"));
  const restart = createUpdateStore(deps);
  await restart.initialize();
  expect(restart.modal.value).toBe(true);
});
it("does not show an update for equal or older releases", async () => {
  for (const v of ["1.1.0", "1.1.1"]) {
    const deps = setup();
    deps.fetch.mockResolvedValue(release(v));
    const u = createUpdateStore(deps);
    await u.initialize();
    expect(u.modal.value).toBe(false);
    expect(u.status.value).toBe("当前已是最新版本");
  }
});
it("retains installed version offline and recovers on a manual retry", async () => {
  const deps = setup();
  deps.fetch.mockRejectedValueOnce(new Error("offline"));
  const u = createUpdateStore(deps);
  await u.initialize();
  expect(u.version.value).toBe("1.1.1");
  expect(u.status.value).toContain("失败");
  expect(u.modal.value).toBe(false);
  await u.check(true);
  expect(u.modal.value).toBe(true);
  expect(u.error.value).toBe("");
});
it("coalesces concurrent checks while honoring a manual request", async () => {
  const deps = setup();
  let resolve!: (r: MobileRelease) => void;
  deps.fetch.mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  localStorage.setItem(
    "wao-mobile:update-reminder:v1",
    JSON.stringify({ version: "1.2.0", until: 2000000000 }),
  );
  const u = createUpdateStore(deps);
  const first = u.check(),
    second = u.check(true);
  resolve(release());
  await Promise.all([first, second]);
  expect(deps.fetch).toHaveBeenCalledTimes(1);
  expect(u.modal.value).toBe(true);
});
it("throttles foreground checks and opens only a validated platform download", async () => {
  const deps = setup(),
    u = createUpdateStore(deps);
  await u.initialize();
  await u.check();
  expect(deps.fetch).toHaveBeenCalledTimes(1);
  await u.download();
  expect(deps.open).toHaveBeenCalledWith(
    "https://wao.wayuwa.cn/downloads/mobile/1.2.0/WAO-1.2.0-android.apk",
  );
  expect(u.modal.value).toBe(false);
});
it("does not call a missing platform build latest", async () => {
  const deps = setup();
  deps.platform = () => "ios";
  const r = release();
  delete r.files.ios;
  deps.fetch.mockResolvedValue(r);
  const u = createUpdateStore(deps);
  await u.initialize();
  expect(u.available.value).toBe(false);
  expect(u.status.value).toContain("暂时没有");
});
it("offers the unsigned iOS package for personal signing", async () => {
  const deps = setup();
  deps.platform = () => "ios";
  const u = createUpdateStore(deps);
  await u.initialize();
  await u.download();
  expect(deps.open).toHaveBeenCalledWith(
    "https://wao.wayuwa.cn/downloads/mobile/1.2.0/WAO-1.2.0-ios-unsigned.ipa",
  );
});
it("allows an automatic foreground retry after an offline startup", async () => {
  const deps = setup();
  deps.fetch.mockRejectedValueOnce(new Error("offline"));
  const u = createUpdateStore(deps);
  await u.initialize();
  deps.now.mockReturnValue(1000000000 + 61000);
  await u.check();
  expect(u.available.value).toBe(true);
  expect(deps.fetch).toHaveBeenCalledTimes(2);
});
