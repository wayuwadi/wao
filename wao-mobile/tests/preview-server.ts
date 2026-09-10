// Explicitly selected local demo mode only. Never included in production bundles.
import type { ChatMessage } from "../src/api/chat.ts";
import type { Plugin } from "vite";
import { localDate } from "../src/domain/models.ts";
const today = localDate();
export function previewServer(): Plugin {
  const messages: ChatMessage[] = [
    {
      id: "demo-chat-1",
      author: { id: "demo-other", name: "小雨" },
      content: "忙完这阵，一起去散个步吧。🌿",
      createdAt: new Date(Date.now() - 1800000).toISOString(),
      deleted: false,
      read: false,
      readCount: 0,
      reactions: [],
    },
    {
      id: "demo-chat-2",
      author: { id: "demo-me", name: "演示用户" },
      content: "好呀，今天的小事都快做完了。",
      createdAt: new Date(Date.now() - 900000).toISOString(),
      deleted: false,
      read: true,
      readCount: 1,
      reactions: [
        {
          emojiType: "HEART",
          emoji: "❤️",
          count: 1,
          reacted: false,
          names: ["小雨"],
        },
      ],
    },
    {
      id: "demo-chat-3",
      author: { id: "demo-other", name: "小雨" },
      content:
        "顺便把周末的计划记下来，不赶时间，慢慢来。\n[周末清单](/api/files/demo-file/download)",
      createdAt: new Date().toISOString(),
      deleted: false,
      read: false,
      readCount: 0,
      reactions: [],
    },
  ];
  const attempts = new Map<string, ChatMessage>();
  const tasks = [
    {
      id: "demo-t1",
      title: "给新的一周留一点计划",
      project: "demo-life",
      owner: "demo-me",
      status: "待办",
      pr: "P1",
      due: today,
      desc: "先挑出最想完成的三件事。",
    },
    {
      id: "demo-t2",
      title: "傍晚去公园走一走",
      project: "demo-life",
      owner: "demo-me",
      status: "待办",
      pr: "P2",
      due: today,
      desc: "",
    },
    {
      id: "demo-t3",
      title: "读完床头的那一章",
      project: "demo-learn",
      owner: "demo-me",
      status: "已完成",
      pr: "P3",
      due: today,
      desc: "",
    },
  ];
  const notes = [
    {
      id: "demo-n1",
      title: "平常日子里的小小确定",
      author: "demo-me",
      created: today,
      updated: today,
      kbId: "kb-notes",
      rev: 1,
      blocks: [
        {
          p: "早上的阳光刚好落在书桌上。\n泡一杯茶，慢慢做完手头的事，也是一种很好的开始。",
        },
      ],
    },
    {
      id: "demo-n2",
      title: "周末，想试试这些事",
      author: "demo-me",
      created: today,
      updated: today,
      kbId: "kb-notes",
      rev: 1,
      blocks: [
        {
          p: "去没走过的街区散步，找一家安静的小店。\n不把行程排满，留一些时间给偶然。",
        },
      ],
    },
  ];
  return {
    name: "wao-explicit-demo",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        if (req.url?.startsWith("/downloads/mobile/latest.json")) {
          res.setHeader("Content-Type", "application/json");
          res.end(
            JSON.stringify({
              version: "1.2.0",
              publishedAt: "2026-09-11T00:00:00Z",
              files: {
                android: {
                  name: "WAO-1.2.0-android.apk",
                  size: 3200000,
                  sha256: "0".repeat(64),
                },
                ios: {
                  name: "WAO-1.2.0-ios-unsigned.ipa",
                  size: 600000,
                  sha256: "0".repeat(64),
                },
              },
            }),
          );
          return;
        }
        if (!req.url?.startsWith("/api/")) return next();
        if (req.url === "/api/files/demo-file/download") {
          res.setHeader("Content-Type", "text/plain");
          res.end("周末清单：散步、读书、一起做饭。");
          return;
        }
        let data: any = {};
        if (req.method !== "GET") {
          let raw = "";
          for await (const chunk of req) raw += chunk;
          data =
            raw && req.headers["content-type"]?.includes("application/json")
              ? JSON.parse(raw)
              : {};
        }
        const path = req.url.split("?")[0];
        let result: unknown = null;
        if (path === "/api/chat/messages" && req.method === "GET")
          result = messages;
        else if (path === "/api/chat/messages" && req.method === "POST") {
          const prior = attempts.get(data.clientToken);
          if (prior) result = prior;
          else {
            const replied = messages.find((m) => m.id === data.replyToId);
            const message: ChatMessage = {
              id: `demo-chat-${Date.now()}`,
              author: { id: "demo-me", name: "演示用户" },
              content: data.content,
              createdAt: new Date().toISOString(),
              deleted: false,
              read: true,
              readCount: 0,
              reactions: [],
              ...(replied
                ? {
                    replyTo: {
                      id: replied.id,
                      authorName: replied.author.name,
                      preview: replied.content,
                    },
                  }
                : {}),
            };
            messages.push(message);
            attempts.set(data.clientToken, message);
            result = message;
          }
        } else if (path === "/api/chat/messages/read") {
          for (const m of messages) {
            m.read = true;
            if (
              m.id ===
              new URL(req.url, "http://localhost").searchParams.get("through")
            )
              break;
          }
        } else if (path.match(/^\/api\/chat\/messages\/[^/]+\/reactions\//)) {
          const parts = path.split("/"),
            message = messages.find((m) => m.id === parts[4])!,
            kind = parts[6]!;
          const reaction = message.reactions.find((r) => r.emojiType === kind);
          if (reaction) {
            reaction.reacted = !reaction.reacted;
            reaction.count += reaction.reacted ? 1 : -1;
          } else
            message.reactions.push({
              emojiType: kind,
              emoji: (
                {
                  THUMBSUP: "👍",
                  HEART: "❤️",
                  SMILE: "😊",
                  DONE: "✅",
                } as Record<string, string>
              )[kind]!,
              count: 1,
              reacted: true,
              names: ["演示用户"],
            });
          result = message;
        } else if (path === "/api/images" || path === "/api/files") {
          res.statusCode = 400;
          res.setHeader("Content-Type", "application/json");
          res.end(
            JSON.stringify({
              code: 1,
              message: "演示环境不保存附件；真实账号支持上传。",
            }),
          );
          return;
        } else if (
          path === "/api/auth/session" ||
          path === "/api/auth/email/login"
        )
          result = {
            accountId: "demo-account",
            member: {
              id: "demo-me",
              name: "演示用户",
              initial: "日",
              color: "#50785f",
            },
            identities: [],
          };
        else if (path === "/api/projects")
          result = [
            { id: "demo-life", name: "生活日常", status: "进行中" },
            { id: "demo-learn", name: "自我生长", status: "进行中" },
          ];
        else if (path === "/api/tasks" && req.method === "GET") result = tasks;
        else if (path === "/api/tasks" && req.method === "POST") {
          result = { ...data, id: `demo-${Date.now()}`, status: "待办" };
          tasks.unshift(result as (typeof tasks)[number]);
        } else if (path.startsWith("/api/tasks/")) {
          const item = tasks.find((t) => t.id === path.split("/").at(-1));
          Object.assign(item!, data);
          result = item;
        } else if (path === "/api/docs") result = notes;
        else if (path.startsWith("/api/docs/notes/")) {
          const id = path.split("/").at(-1)!;
          const old = notes.find((n) => n.id === id);
          if (old) {
            Object.assign(old, data);
            old.rev++;
            result = old;
          } else {
            result = {
              ...data,
              id,
              author: "demo-me",
              created: today,
              updated: today,
              kbId: "kb-notes",
              rev: 1,
            };
            notes.unshift(result as (typeof notes)[number]);
          }
        } else if (path.startsWith("/api/docs/")) {
          const item = notes.find((n) => n.id === path.split("/").at(-1));
          if (req.method === "PATCH") {
            Object.assign(item!, data);
            item!.rev++;
          }
          result = item;
        }
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ code: 0, data: result }));
      });
    },
  };
}
