// @vitest-environment jsdom
import { IDBFactory } from "fake-indexeddb";
import { afterEach, expect, it, vi } from "vitest";
import { createGameMemory } from "../../host/useGameMemory";
import {
  exportMemory,
  MemoryStore,
  parseMemory,
  type MemoryRecord,
} from "../../host/memory";
import { freshMemory } from "../dino/save";
import { freshRiftMemory, rewardRoom, startChapter } from "./save";

afterEach(() => vi.unstubAllGlobals());
it("双游戏独立保存；全部导出到独立存储后从下一房继续，恐龙正式v1不变", async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const disk = new MemoryStore(new IDBFactory());
  const dino = createGameMemory(disk),
    rift = createGameMemory(disk, "rift");
  await dino.initialize();
  await rift.initialize();
  dino.update({ ...freshMemory(), practiceComplete: true });
  await dino.flush();
  const begun = startChapter(freshRiftMemory(), 0, 43);
  const won = rewardRoom(begun, begun.checkpoint!, 12345).memory;
  rift.update(won);
  await rift.flush();
  // Deep copy: a renderer mutating its sent object cannot change the saved draft.
  won.heroes.blade.xp = 999;
  expect(rift.draft.value.heroes.blade.xp).toBe(28);
  const file = parseMemory(exportMemory([dino.current(), rift.current()]));
  const other = new MemoryStore(new IDBFactory());
  await other.replace(file.games, { dino: 0, rift: 0 });
  const continuation = createGameMemory(other, "rift");
  await continuation.initialize();
  expect(continuation.draft.value.checkpoint?.room).toBe(1);
  const next = rewardRoom(
    continuation.draft.value,
    continuation.draft.value.checkpoint!,
    18000,
  ).memory;
  continuation.update(next);
  await continuation.flush();
  expect(
    (await other.all()).find((r) => r.gameId === "rift")?.payload.checkpoint
      ?.room,
  ).toBe(2);
  expect(
    (await other.all()).find((r) => r.gameId === "dino")?.payload
      .practiceComplete,
  ).toBe(true);
});
it("全部导入任一游戏CAS冲突时整批和备份回滚，成功时保留本机档与未保存草稿", async () => {
  const store = new MemoryStore(new IDBFactory());
  const dino = await store.save(freshMemory(), 0);
  const rift = await store.save(freshRiftMemory(), 0, "rift");
  const changed: MemoryRecord<"rift"> = {
    ...rift,
    payload: { ...rift.payload, muted: false },
  };
  await expect(
    store.replace([dino, changed], { dino: 1, rift: 0 }, true, [changed]),
  ).rejects.toMatchObject({ kind: "conflict" });
  expect(await store.all()).toEqual([dino, rift]);
  expect(await store.backups()).toHaveLength(0);
  const results = await store.replace(
    [dino, changed],
    { dino: 1, rift: 1 },
    true,
    [changed],
  );
  expect(results.map((r) => r.revision)).toEqual([2, 2]);
  expect(await store.backups()).toHaveLength(3);
  expect(
    (await store.backups()).some(
      (b) => b.original.gameId === "rift" && b.original.payload.muted === false,
    ),
  ).toBe(true);
});
it("跨页拒绝覆盖、会话模式及坏档均不伤原有RPG成长", async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const factory = new IDBFactory(),
    disk = new MemoryStore(factory);
  const a = createGameMemory(disk, "rift"),
    b = createGameMemory(new MemoryStore(factory), "rift");
  await a.initialize();
  await b.initialize();
  a.update(startChapter(freshRiftMemory(), 0, 42));
  await a.flush();
  b.update({ ...freshRiftMemory(), muted: false });
  await b.flush();
  expect(b.status.value).toBe("conflict");
  expect(parseMemory(b.exportCurrent()).games[0]?.payload.muted).toBe(false);
  await b.sessionOnly();
  b.update(startChapter(b.draft.value, 0, 100));
  await b.flush();
  expect(
    (await disk.all()).find((r) => r.gameId === "rift")?.payload.checkpoint
      ?.seed,
  ).toBe(42);
  const original = await disk.all();
  const file = JSON.parse(exportMemory(original));
  for (const bad of [
    { ...file, games: [{ ...file.games[0], rulesVersion: 99 }] },
    {
      ...file,
      games: [
        { ...file.games[0], payload: { ...freshRiftMemory(), materials: -1 } },
      ],
    },
    {
      ...file,
      games: [
        {
          ...file.games[0],
          payload: {
            ...freshRiftMemory(),
            chapterWins: [0, 1, 0],
            bestTimes: [0, 300, 0],
          },
        },
      ],
    },
  ])
    expect(() => parseMemory(JSON.stringify(bad))).toThrow();
  expect(await disk.all()).toEqual(original);
});
