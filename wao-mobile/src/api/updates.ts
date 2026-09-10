import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { App } from "@capacitor/app";
import { InAppBrowser } from "@capacitor/inappbrowser";
import pkg from "../../package.json";
import { API_ORIGIN } from "./client";
export type Platform = "android" | "ios";
export interface ReleaseFile {
  name: string;
  size: number;
  sha256: string;
}
export interface MobileRelease {
  version: string;
  publishedAt: string;
  files: Partial<Record<Platform, ReleaseFile>>;
}
const pattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
export function versionParts(value: string): number[] {
  if (!pattern.test(value)) throw new Error("版本格式无效");
  const parts = value.split(".").map(Number);
  if (parts[0]! > 2000 || parts[1]! > 999 || parts[2]! > 999)
    throw new Error("版本超出支持范围");
  return parts;
}
export function newer(latest: string, current: string) {
  const a = versionParts(latest),
    b = versionParts(current);
  const index = a.findIndex((n, i) => n !== b[i]);
  return index >= 0 && a[index]! > b[index]!;
}
export function parseRelease(value: unknown): MobileRelease {
  const data = value as MobileRelease;
  if (
    !data ||
    typeof data.version !== "string" ||
    typeof data.publishedAt !== "string" ||
    !Number.isFinite(Date.parse(data.publishedAt)) ||
    !data.files
  )
    throw new Error("更新信息不完整，请稍后重试");
  versionParts(data.version);
  const files: MobileRelease["files"] = {};
  for (const platform of ["android", "ios"] as const) {
    const file = data.files[platform];
    if (!file) continue;
    const expected = `WAO-${data.version}-${platform === "android" ? "android.apk" : "ios-unsigned.ipa"}`;
    if (
      file.name !== expected ||
      !Number.isSafeInteger(file.size) ||
      file.size <= 0 ||
      typeof file.sha256 !== "string" ||
      !/^[a-f0-9]{64}$/i.test(file.sha256)
    )
      throw new Error("更新文件信息无效，请稍后重试");
    files[platform] = { name: file.name, size: file.size, sha256: file.sha256 };
  }
  if (!files.android && !files.ios) throw new Error("暂时没有可用的更新包");
  return { version: data.version, publishedAt: data.publishedAt, files };
}
export async function fetchRelease(): Promise<MobileRelease> {
  const path = `/downloads/mobile/latest.json?t=${Date.now()}`;
  if (Capacitor.isNativePlatform()) {
    const result = await CapacitorHttp.get({
      url: API_ORIGIN + path,
      headers: { "Cache-Control": "no-cache" },
      responseType: "json",
      connectTimeout: 15000,
      readTimeout: 15000,
      disableRedirects: true,
    });
    if (result.status !== 200)
      throw new Error("检查更新失败，请检查网络后重试");
    return parseRelease(result.data);
  }
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(path, {
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      signal: controller.signal,
    });
    if (!response.ok) throw new Error("检查更新失败，请检查网络后重试");
    return parseRelease(await response.json());
  } finally {
    clearTimeout(timer);
  }
}
export async function installedVersion(): Promise<string> {
  const version = Capacitor.isNativePlatform()
    ? (await App.getInfo()).version
    : import.meta.env.VITE_MOBILE_VERSION || pkg.version;
  versionParts(version);
  return version;
}
export function downloadUrl(release: MobileRelease, platform: Platform) {
  const verified = parseRelease(release),
    file = verified.files[platform];
  if (!file) throw new Error("此平台的更新包暂不可用");
  return `${API_ORIGIN}/downloads/mobile/${verified.version}/${file.name}`;
}
export async function openDownload(url: string) {
  // All callers use our validated release manifest or the fixed download page.
  if (Capacitor.isNativePlatform())
    await InAppBrowser.openInExternalBrowser({ url });
  else {
    const child = window.open(url, "_blank");
    if (!child) throw new Error("浏览器阻止了下载窗口，请允许弹出窗口后重试");
    child.opener = null;
  }
}
