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
import { freshStarfieldMemory, isStarfieldMemory } from "./save";
import { act, currentActor, enemyAct, startBattle } from "./engine";
import { childEvent, envelope } from "../../protocol/host";

afterEach(() => vi.unstubAllGlobals());
function checkpoint() {
  let m = freshStarfieldMemory();
  m.accepted[0] = true;
  m = startBattle(m, 0, "aid", 42);
  while (m.battle!.phase === "enemy") m = enemyAct(m);
  return act(m, "bind", "e0");
}
it("三款全量导出到独立存储，星野严格从已提交行动之后继续，前两款不变", async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  const disk = new MemoryStore(new IDBFactory());
  await disk.save(
    { ...freshMemory(), best: 77, totalDistance: 77, runs: 1 },
    0,
  );
  await disk.save(freshRiftMemory(), 0, "rift");
  const game = createGameMemory(disk, "starfield");
  await game.initialize();
  const snapshot = checkpoint();
  game.update(snapshot);
  await game.flush();
  snapshot.battle!.actors[0]!.hp = 1;
  expect(game.draft.value.battle!.actors[0]!.hp).not.toBe(1);
  const file = parseMemory(exportMemory(await disk.all()));
  expect(file.games.map((g) => g.gameId)).toEqual([
    "dino",
    "rift",
    "starfield",
  ]);
  const other = new MemoryStore(new IDBFactory());
  await other.replace(file.games, { dino: 0, rift: 0, starfield: 0 });
  const restored = createGameMemory(other, "starfield");
  await restored.initialize();
  expect(restored.draft.value.battle).toEqual(game.draft.value.battle);
  expect(currentActor(restored.draft.value.battle!)!.kind).toBe("sol");
  const continued = act(restored.draft.value, "returnblade", "e0");
  restored.update(continued);
  await restored.flush();
  expect(continued.battle!.actions).toBe(game.draft.value.battle!.actions + 1);
  expect(summary(restored.current())).toContain("次已确认行动");
  expect(
    (await other.all())
      .filter((g) => g.gameId !== "starfield")
      .map((g) => g.payload),
  ).toEqual(
    file.games.filter((g) => g.gameId !== "starfield").map((g) => g.payload),
  );
});
it("三款批量CAS原子回滚，恢复前备份本机和草稿；非法行动快照不能覆盖", async () => {
  const disk = new MemoryStore(new IDBFactory());
  const records = [
    await disk.save(freshMemory(), 0),
    await disk.save(freshRiftMemory(), 0, "rift"),
    await disk.save(checkpoint(), 0, "starfield"),
  ];
  const original = await disk.all();
  await expect(
    disk.replace(records, { dino: 1, rift: 1, starfield: 0 }),
  ).rejects.toMatchObject({ kind: "conflict" });
  expect(await disk.all()).toEqual(original);
  expect(await disk.backups()).toHaveLength(0);
  const draft = {
    ...records[2]!,
    gameId: "starfield" as const,
    payload: { ...checkpoint(), muted: false },
  };
  await disk.replace(records, { dino: 1, rift: 1, starfield: 1 }, true, [
    draft,
  ]);
  expect(await disk.backups()).toHaveLength(4);
  const valid = exportMemory([records[2]!]);
  for (const mutate of [
    (v: any) => (v.games[0].rulesVersion = 99),
    (v: any) => (v.games[0].payload.battle.actors[0].hp = -1),
    (v: any) => (v.games[0].payload.battle.queue[0] = "unknown"),
    (v: any) => (v.games[0].payload.battle.cursor = 999),
    (v: any) => (v.games[0].payload.heroes.sol.weapon = "<script>"),
    (v: any) => (v.games[0].payload.completed[8] = true),
  ]) {
    const v = JSON.parse(valid);
    mutate(v);
    expect(() => parseMemory(JSON.stringify(v))).toThrow();
  }
  expect(isStarfieldMemory(checkpoint())).toBe(true);
});
it("星野协议拒绝其他游戏和损坏快照，仅接受完整规则版本1", () => {
  const value = envelope(
    { type: "checkpoint", payload: checkpoint() } as never,
    "starfield-test-instance",
    1,
    "starfield",
  );
  expect(childEvent(value, "starfield-test-instance", "starfield")).toBe(true);
  expect(childEvent(value, "starfield-test-instance", "rift")).toBe(false);
  expect(
    childEvent(
      { ...value, protocol: 99 },
      "starfield-test-instance",
      "starfield",
    ),
  ).toBe(false);
  expect(
    childEvent(
      { ...value, payload: { ...checkpoint(), sand: -1 } },
      "starfield-test-instance",
      "starfield",
    ),
  ).toBe(false);
});
