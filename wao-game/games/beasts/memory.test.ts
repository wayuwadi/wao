// @vitest-environment jsdom
import { IDBFactory } from "fake-indexeddb";
import { afterEach, expect, it, vi } from "vitest";
import { createGameMemory } from "../../host/useGameMemory";
import {
  exportMemory,
  MemoryStore,
  parseMemory,
  summary,
} from "../../host/memory";
import { freshMemory } from "../dino/save";
import { freshRiftMemory } from "../rift/save";
import { freshStarfieldMemory } from "../starfield/save";
import { beginExpedition, chooseRoute, freshBeastMemory } from "./save";
import { startBattle, advance } from "./engine";
import { childEvent, envelope } from "../../protocol/host";
afterEach(() => vi.unstubAllGlobals());
const checkpoint = () =>
  chooseRoute(beginExpedition(freshBeastMemory(), 0), "track");
it("四款全量到独立存储，幻兽恢复相同节点而不伪装精确战斗快照", async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const disk = new MemoryStore(new IDBFactory());
  await disk.save(
    { ...freshMemory(), best: 77, totalDistance: 77, runs: 1 },
    0,
  );
  await disk.save(freshRiftMemory(), 0, "rift");
  await disk.save(freshStarfieldMemory(), 0, "starfield");
  const m = createGameMemory(disk, "beasts");
  await m.initialize();
  m.update(checkpoint());
  await m.flush();
  const runtime = startBattle(m.draft.value)!;
  advance(runtime, 0.1);
  const records = parseMemory(exportMemory(await disk.all()));
  expect(records.games).toHaveLength(4);
  const other = new MemoryStore(new IDBFactory());
  await other.replace(records.games, {
    dino: 0,
    rift: 0,
    starfield: 0,
    beasts: 0,
  });
  const restored = createGameMemory(other, "beasts");
  await restored.initialize();
  expect(restored.draft.value).toEqual(m.draft.value);
  expect(startBattle(restored.draft.value)!.seconds).toBe(0);
  expect(summary(restored.current())).toContain("开战前");
  expect(
    (await other.all())
      .filter((r) => r.gameId !== "beasts")
      .map((r) => r.payload),
  ).toEqual(
    records.games.filter((r) => r.gameId !== "beasts").map((r) => r.payload),
  );
});
it("批量版本冲突原子回滚、覆盖备份、错误记忆均不损伤原档", async () => {
  const disk = new MemoryStore(new IDBFactory());
  const record = await disk.save(checkpoint(), 0, "beasts");
  const dino = await disk.save(freshMemory(), 0);
  const original = await disk.all();
  await expect(
    disk.replace([dino, record], { dino: 1, beasts: 0 }),
  ).rejects.toMatchObject({ kind: "conflict" });
  expect(await disk.all()).toEqual(original);
  await disk.replace([dino, record], { dino: 1, beasts: 1 }, true);
  expect(await disk.backups()).toHaveLength(2);
  const text = exportMemory([record]);
  for (const mutate of [
    (v: any) => (v.games[0].rulesVersion = 2),
    (v: any) => (v.games[0].payload.expedition.seed = -1),
    (v: any) => (v.games[0].payload.team[0] = "<script>"),
    (v: any) => (v.games[0].payload.food = -1),
    (v: any) => (v.games[0].payload.roster[0].level = 9),
  ]) {
    const v = JSON.parse(text);
    mutate(v);
    expect(() => parseMemory(JSON.stringify(v))).toThrow();
  }
  expect(() => parseMemory(text.slice(0, -2))).toThrow();
  expect(
    (await disk.all()).find((r) => r.gameId === "beasts")!.payload,
  ).toEqual(record.payload);
});
it("协议明确区分游戏、版本及损坏节点", () => {
  const id = "beasts-test-instance";
  const value = envelope(
    { type: "checkpoint", payload: checkpoint() } as never,
    id,
    1,
    "beasts",
  );
  expect(childEvent(value, id, "beasts")).toBe(true);
  expect(childEvent(value, id, "starfield")).toBe(false);
  expect(childEvent({ ...value, protocol: 99 }, id, "beasts")).toBe(false);
  expect(
    childEvent(
      { ...value, payload: { ...checkpoint(), food: -1 } },
      id,
      "beasts",
    ),
  ).toBe(false);
});
