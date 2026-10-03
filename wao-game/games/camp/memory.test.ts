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
import { freshBeastMemory } from "../beasts/save";
import { beginRun, build, freshCampMemory } from "./save";
import { startBattle, advance } from "./engine";
import { childEvent, envelope } from "../../protocol/host";
afterEach(() => vi.unstubAllGlobals());
const checkpoint = () => {
  const m = beginRun(freshCampMemory(), 0, "campaign");
  build(m.run!, 2, "bolt");
  return m;
};
it("五款全量导出到独立存储，营地保留波前塔和资金，其他四款不变", async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const disk = new MemoryStore(new IDBFactory());
  await disk.save(
    { ...freshMemory(), best: 77, totalDistance: 77, runs: 1 },
    0,
  );
  await disk.save(freshRiftMemory(), 0, "rift");
  await disk.save(freshStarfieldMemory(), 0, "starfield");
  await disk.save(freshBeastMemory(), 0, "beasts");
  const m = createGameMemory(disk, "camp");
  await m.initialize();
  m.update(checkpoint());
  await m.flush();
  const runtime = startBattle(m.draft.value.run!);
  advance(runtime, 0.1);
  const records = parseMemory(exportMemory(await disk.all()));
  expect(records.games).toHaveLength(5);
  const other = new MemoryStore(new IDBFactory());
  await other.replace(records.games, {
    dino: 0,
    rift: 0,
    starfield: 0,
    beasts: 0,
    camp: 0,
  });
  const restored = createGameMemory(other, "camp");
  await restored.initialize();
  expect(restored.draft.value).toEqual(m.draft.value);
  expect(startBattle(restored.draft.value.run!).time).toBe(0);
  expect(summary(restored.current())).toContain("第1波");
  expect(
    (await other.all())
      .filter((r) => r.gameId !== "camp")
      .map((r) => r.payload),
  ).toEqual(
    records.games.filter((r) => r.gameId !== "camp").map((r) => r.payload),
  );
});
it("冲突整体回滚，覆盖前备份，损坏字段和版本不能损伤原档", async () => {
  const disk = new MemoryStore(new IDBFactory());
  const record = await disk.save(checkpoint(), 0, "camp"),
    dino = await disk.save(freshMemory(), 0);
  const original = await disk.all();
  await expect(
    disk.replace([dino, record], { dino: 1, camp: 0 }),
  ).rejects.toMatchObject({ kind: "conflict" });
  expect(await disk.all()).toEqual(original);
  await disk.replace([dino, record], { dino: 1, camp: 1 }, true);
  expect(await disk.backups()).toHaveLength(2);
  const text = exportMemory([record]);
  for (const mutate of [
    (v: any) => (v.games[0].rulesVersion = 2),
    (v: any) => (v.games[0].payload.run.wave = 13),
    (v: any) => (v.games[0].payload.run.towers[0].kind = "<script>"),
    (v: any) => (v.games[0].payload.run.gold = -1),
    (v: any) =>
      v.games[0].payload.run.towers.push(v.games[0].payload.run.towers[0]),
    (v: any) => (v.games[0].payload.run.towers[0].branch = 0),
  ]) {
    const v = JSON.parse(text);
    mutate(v);
    expect(() => parseMemory(JSON.stringify(v))).toThrow();
  }
  expect(() => parseMemory(text.slice(0, -2))).toThrow();
  expect((await disk.all()).find((r) => r.gameId === "camp")!.payload).toEqual(
    record.payload,
  );
});
it("营地消息拒绝其他游戏、协议版本和畸形波前状态", () => {
  const id = "camp-test-instance";
  const value = envelope(
    { type: "checkpoint", payload: checkpoint() } as never,
    id,
    1,
    "camp",
  );
  expect(childEvent(value, id, "camp")).toBe(true);
  expect(childEvent(value, id, "beasts")).toBe(false);
  expect(childEvent({ ...value, protocol: 99 }, id, "camp")).toBe(false);
  expect(
    childEvent(
      { ...value, payload: { ...checkpoint(), wins: [0, 1, 0] } },
      id,
      "camp",
    ),
  ).toBe(false);
});
