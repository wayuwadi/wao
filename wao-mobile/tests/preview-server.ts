// Explicitly selected local demo mode only. Never included in production bundles.
import type { Plugin } from "vite";
import { localDate } from "../src/domain/models.ts";
const today = localDate();
export function previewServer(): Plugin {
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
        if (!req.url?.startsWith("/api/")) return next();
        let data: any = {};
        if (req.method !== "GET") {
          let raw = "";
          for await (const chunk of req) raw += chunk;
          data = raw ? JSON.parse(raw) : {};
        }
        const path = req.url.split("?")[0];
        let result: unknown = null;
        if (path === "/api/auth/session" || path === "/api/auth/email/login")
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
