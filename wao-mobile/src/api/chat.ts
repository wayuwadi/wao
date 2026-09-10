import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { Filesystem, Directory } from "@capacitor/filesystem";
import { Share } from "@capacitor/share";
import { API_ORIGIN, ApiError, request } from "./client";
export interface ChatMessage {
  id: string;
  author: { id: string; name: string; initial?: string; color?: string };
  content: string;
  createdAt: string;
  deleted: boolean;
  read: boolean;
  readCount: number;
  imageUrl?: string;
  imageName?: string;
  replyTo?: { id: string; authorName: string; preview: string };
  reactions: {
    emojiType: string;
    emoji: string;
    count: number;
    reacted: boolean;
    names: string[];
  }[];
}
export interface ChatInput {
  clientToken: string;
  content: string;
  replyToId?: string;
}
export const chatApi = {
  messages: () => request<ChatMessage[]>("/api/chat/messages"),
  send: (input: ChatInput) =>
    request<ChatMessage>("/api/chat/messages", "POST", input),
  read: (through: string) =>
    request<void>(
      `/api/chat/messages/read?through=${encodeURIComponent(through)}`,
      "POST",
    ),
  react: (id: string, emoji: string) =>
    request<ChatMessage>(
      `/api/chat/messages/${encodeURIComponent(id)}/reactions/${encodeURIComponent(emoji)}`,
      "POST",
    ),
};
export function safeLink(raw: string): string | null {
  try {
    const url = new URL(raw, API_ORIGIN);
    return url.protocol === "https:" && !url.username && !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}
export function assetPath(raw: string): string | null {
  const url = safeLink(raw);
  if (!url) return null;
  const parsed = new URL(url);
  return parsed.origin === API_ORIGIN &&
    /^\/api\/(images\/[A-Za-z0-9_-]+\/raw|files\/[A-Za-z0-9_-]+\/download)$/.test(
      parsed.pathname,
    ) &&
    !parsed.search &&
    !parsed.hash
    ? parsed.pathname
    : null;
}
export type MessagePart = {
  kind: "text" | "link" | "image";
  text: string;
  url?: string;
};
export function messageParts(content: string): MessagePart[] {
  const parts: MessagePart[] = [],
    pattern = /(!?)\[([^\]\n]*)\]\(([^\s)]+)\)/g;
  let last = 0;
  for (const match of content.matchAll(pattern)) {
    if (match.index! > last)
      parts.push({ kind: "text", text: content.slice(last, match.index) });
    const url = safeLink(match[3]!);
    parts.push(
      url
        ? {
            kind:
              match[1] && assetPath(url)?.startsWith("/api/images/")
                ? "image"
                : "link",
            text: match[2] || "附件",
            url,
          }
        : { kind: "text", text: match[0] },
    );
    last = match.index! + match[0].length;
  }
  if (last < content.length)
    parts.push({ kind: "text", text: content.slice(last) });
  return parts;
}
function checked<T>(
  status: number,
  body: { code?: number; data?: T; message?: string },
): T {
  if (status === 401) window.dispatchEvent(new Event("wao:unauthorized"));
  if (status < 200 || status >= 300 || body?.code !== 0 || !body.data)
    throw new ApiError(body?.message || "附件请求失败，请重试", status);
  return body.data;
}
async function base64(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]!);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}
export async function uploadChatAttachment(
  file: File,
  image: boolean,
): Promise<{ id: string; name: string }> {
  if (!file.size || file.size > (image ? 10 : 20) * 1024 * 1024)
    throw new Error(
      `请选择不超过 ${image ? 10 : 20}MB 的${image ? "图片" : "文件"}`,
    );
  if (image && !/^image\/(png|jpeg|gif|webp|heic|heif)$/i.test(file.type))
    throw new Error("请选择常见格式的图片");
  const path = image ? "/api/images" : "/api/files";
  const name = file.name.replace(/[\r\n"\\]/g, "_") || "attachment";
  if (Capacitor.isNativePlatform()) {
    const result = await CapacitorHttp.request({
      url: API_ORIGIN + path,
      method: "POST",
      dataType: "formData",
      headers: { "Content-Type": "multipart/form-data" },
      data: [
        {
          type: "base64File",
          key: "file",
          value: await base64(file),
          fileName: name,
          contentType: file.type || "application/octet-stream",
        },
      ],
      connectTimeout: 15000,
      readTimeout: 120000,
      responseType: "json",
      disableRedirects: true,
    });
    return checked(result.status, result.data);
  }
  const body = new FormData();
  body.append("file", file, name);
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), 120000);
  try {
    const response = await fetch(path, {
      method: "POST",
      body,
      credentials: "include",
      redirect: "error",
      signal: controller.signal,
    });
    return checked(response.status, await response.json());
  } finally {
    clearTimeout(timer);
  }
}
export async function readChatAsset(raw: string): Promise<Blob> {
  const path = assetPath(raw);
  if (!path) throw new Error("不支持的附件地址");
  if (!Capacitor.isNativePlatform()) {
    const response = await fetch(path, {
      credentials: "include",
      redirect: "error",
    });
    if (response.status === 401)
      window.dispatchEvent(new Event("wao:unauthorized"));
    if (!response.ok) throw new Error("附件读取失败，请重试");
    return response.blob();
  }
  const response = await CapacitorHttp.get({
    url: API_ORIGIN + path,
    responseType: "blob",
    connectTimeout: 15000,
    readTimeout: 60000,
    disableRedirects: true,
  });
  if (response.status === 401)
    window.dispatchEvent(new Event("wao:unauthorized"));
  if (response.status !== 200 || typeof response.data !== "string")
    throw new Error("附件读取失败，请重试");
  const bytes = Uint8Array.from(atob(response.data), (char) =>
    char.charCodeAt(0),
  );
  const type =
    Object.entries(response.headers).find(
      ([key]) => key.toLowerCase() === "content-type",
    )?.[1] || "application/octet-stream";
  return new Blob([bytes], { type });
}
export async function saveChatFile(
  url: string,
  name: string,
  allowed: () => boolean = () => true,
) {
  const blob = await readChatAsset(url);
  if (!allowed()) return;
  if (Capacitor.isNativePlatform()) {
    // Keep recent shares alive for receiving apps, prune only this feature's old cache files.
    const files = await Filesystem.readdir({
      directory: Directory.Cache,
      path: "",
    });
    for (const file of files.files) {
      const stamp = /^chat-(\d+)-/.exec(file.name)?.[1];
      if (stamp && Date.now() - Number(stamp) > 86400000)
        await Filesystem.deleteFile({
          directory: Directory.Cache,
          path: file.name,
        }).catch(() => {});
    }
    const safeName =
      name.replace(/[^\p{L}\p{N}._-]/gu, "_").slice(0, 100) || "attachment";
    const path = `chat-${Date.now()}-${safeName}`;
    const result = await Filesystem.writeFile({
      directory: Directory.Cache,
      path,
      data: await base64(blob),
    });
    if (!allowed()) {
      await Filesystem.deleteFile({ directory: Directory.Cache, path }).catch(
        () => {},
      );
      return;
    }
    await Share.share({
      title: name,
      files: [result.uri],
      dialogTitle: "保存或分享文件",
    });
  } else {
    const object = URL.createObjectURL(blob),
      anchor = document.createElement("a");
    anchor.href = object;
    anchor.download = name;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(object), 1000);
  }
}
