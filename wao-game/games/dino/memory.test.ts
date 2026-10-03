import { IDBFactory } from "fake-indexeddb";
import { describe, expect, it } from "vitest";
import {
  exportMemory,
  MemoryStore,
  parseMemory,
  summary,
  type MemoryRecord,
} from "../../host/memory";
import { freshMemory } from "./save";
const record = (): MemoryRecord<"dino"> => ({
  gameId: "dino",
  rulesVersion: 1,
  savedAt: new Date().toISOString(),
  revision: 0,
  payload: freshMemory(),
});
describe("本机记忆事务（模拟 IndexedDB，不冒充浏览器验收）", () => {
  it("读取另一页先备份本页草稿，预览过期拒绝替换且保留原档", async () => {
    const store = new MemoryStore(new IDBFactory());
    const disk = await store.save({ ...freshMemory(), muted: false }, 0);
    const draft = {
      ...record(),
      payload: { ...freshMemory(), practiceComplete: true },
    };
    await expect(store.readWithBackup(draft, 0)).rejects.toMatchObject({
      kind: "conflict",
    });
    expect(await store.backups()).toHaveLength(0);
    expect(await store.readWithBackup(draft, 1)).toEqual(disk);
    expect((await store.backups())[0]?.original).toEqual(draft);
    expect(await store.all()).toEqual([disk]);
    await store.replace([draft], { dino: 1 });
    expect((await store.all()).find(r => r.gameId === "dino")?.payload.practiceComplete).toBe(true);
  });
  it("账号无关；关闭重开保留，导出到独立存储后继续", async () => {
    const a = new IDBFactory(),
      store = new MemoryStore(a);
    const saved = await store.save(
      { ...freshMemory(), practiceComplete: true },
      0,
    );
    await store.close();
    const reopened = new MemoryStore(a);
    expect(await reopened.all()).toEqual([saved]);
    const parsed = parseMemory(exportMemory(await reopened.all()));
    const other = new MemoryStore(new IDBFactory());
    await other.replace(parsed.games, { dino: 0 });
    expect((await other.all()).find(r => r.gameId === "dino")?.payload.practiceComplete).toBe(true);
    const next = await other.save(
      { ...parsed.games.find(r => r.gameId === "dino")!.payload, best: 52, totalDistance: 52, runs: 1 },
      1,
    );
    expect(next.revision).toBe(2);
    expect(summary(next)).toContain("52 米");
  });
  it("同 revision 两页并发写只有一个成功，失败者可导出完整草稿", async () => {
    const factory = new IDBFactory(),
      a = new MemoryStore(factory),
      b = new MemoryStore(factory);
    const draftA = { ...freshMemory(), practiceComplete: true },
      draftB = { ...freshMemory(), muted: false };
    const results = await Promise.allSettled([
      a.save(draftA, 0),
      b.save(draftB, 0),
    ]);
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(1);
    expect(results.filter((r) => r.status === "rejected")).toHaveLength(1);
    expect(
      parseMemory(exportMemory([{ ...record(), payload: draftB }])).games[0]
        ?.payload,
    ).toEqual(draftB);
    expect((await a.all())[0]?.revision).toBe(1);
  });
  it("覆盖前备份，可恢复原版；取消预览完全不写入", async () => {
    const store = new MemoryStore(new IDBFactory()),
      original = await store.save(freshMemory(), 0);
    const imported = {
      ...record(),
      payload: { ...freshMemory(), practiceComplete: true },
    };
    parseMemory(exportMemory([imported]));
    expect(await store.all()).toEqual([original]);
    await store.replace([imported], { dino: 1 });
    expect((await store.backups())[0]?.original).toEqual(original);
    await store.replace([(await store.backups())[0]!.original], { dino: 2 });
    expect((await store.all())[0]?.payload).toEqual(original.payload);
    expect(await store.backups()).toHaveLength(2);
  });
  it("损坏、版本、重复、非法值、HTML字段、超大档一律拒绝且旧档不变", async () => {
    const store = new MemoryStore(new IDBFactory()),
      original = await store.save(freshMemory(), 0);
    const file = JSON.parse(exportMemory([record()]));
    const corrupt = [
      "{",
      JSON.stringify({ ...file, formatVersion: 999 }),
      JSON.stringify({ ...file, games: [record(), record()] }),
      JSON.stringify({ ...file, games: [{ ...record(), rulesVersion: 2 }] }),
      JSON.stringify({
        ...file,
        games: [{ ...record(), payload: { ...freshMemory(), best: -2 } }],
      }),
      JSON.stringify({
        ...file,
        games: [
          {
            ...record(),
            payload: { ...freshMemory(), html: "<script>alert(1)</script>" },
          },
        ],
      }),
      " ".repeat(10 * 1024 * 1024 + 1),
    ];
    for (const bad of corrupt) expect(() => parseMemory(bad)).toThrow();
    await expect(
      store.replace(
        [{ ...record(), payload: { ...freshMemory(), best: -3 } }],
        { dino: 1 },
      ),
    ).rejects.toThrow();
    expect(await store.all()).toEqual([original]);
    expect(await store.backups()).toHaveLength(0);
  });
  it("存储被拒绝时可保留、导出并重试，不当作空档覆盖", async () => {
    const factory = {
      open() {
        throw new DOMException("拒绝", "SecurityError");
      },
    } as unknown as IDBFactory;
    const store = new MemoryStore(factory);
    await expect(store.all()).rejects.toMatchObject({ kind: "storage" });
    await expect(store.save(freshMemory(), 0)).rejects.toMatchObject({
      kind: "storage",
    });
    expect(parseMemory(exportMemory([record()])).games).toHaveLength(1);
  });
  it("配额失败中止整个导入事务，包括备份，原档不变", async () => {
    const factory = new IDBFactory(),
      store = new MemoryStore(factory),
      original = await store.save(freshMemory(), 0);
    const req = factory.open("wao-game-memory", 1);
    const db = await new Promise<IDBDatabase>((resolve) => {
      req.onsuccess = () => resolve(req.result);
    });
    const tx = db.transaction("games", "readwrite"),
      prototype = Object.getPrototypeOf(tx.objectStore("games"));
    const put = prototype.put;
    prototype.put = () => {
      throw new DOMException("满了", "QuotaExceededError");
    };
    try {
      await expect(
        store.replace(
          [{ ...record(), payload: { ...freshMemory(), muted: false } }],
          { dino: 1 },
        ),
      ).rejects.toMatchObject({ kind: "storage" });
    } finally {
      prototype.put = put;
    }
    expect(await store.all()).toEqual([original]);
    expect(await store.backups()).toHaveLength(0);
    db.close();
  });
});
