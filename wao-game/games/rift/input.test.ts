// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { RiftInput } from "./input";
afterEach(() => {
  document.body.replaceChildren();
  vi.restoreAllMocks();
});
it("只处理舞台焦点；释放、取消触控、失焦和销毁都不残留方向或蓄力", () => {
  const arena = document.createElement("section");
  arena.tabIndex = 0;
  const field = document.createElement("input");
  arena.append(field);
  document.body.append(arena);
  const actions = {
    active: () => true,
    turn: vi.fn(),
    attack: vi.fn(),
    releaseAttack: vi.fn(),
    jump: vi.fn(),
    dodge: vi.fn(),
    skill: vi.fn(),
    pause: vi.fn(),
    restart: vi.fn(),
  };
  const input = new RiftInput(arena, actions);
  const typing = new KeyboardEvent("keydown", {
    code: "KeyJ",
    bubbles: true,
    cancelable: true,
  });
  field.dispatchEvent(typing);
  expect(typing.defaultPrevented).toBe(false);
  expect(actions.attack).not.toHaveBeenCalled();
  arena.dispatchEvent(
    new KeyboardEvent("keydown", { code: "KeyD", bubbles: true }),
  );
  expect(input.controls.right).toBe(true);
  window.dispatchEvent(new KeyboardEvent("keyup", { code: "KeyD" }));
  expect(input.controls.right).toBe(false);
  expect(actions.turn).toHaveBeenLastCalledWith(1);
  input.touchDown("left");
  input.touchUp("left");
  expect(input.controls.left).toBe(false);
  expect(actions.turn).toHaveBeenLastCalledWith(-1);
  input.touchDown("attack");
  expect(input.controls.attackHeld).toBe(true);
  input.touchUp("attack", true);
  expect(actions.releaseAttack).not.toHaveBeenCalled();
  expect(input.controls.right).toBe(false);
  input.touchDown("left");
  window.dispatchEvent(new Event("blur"));
  expect(input.controls.left).toBe(false);
  input.touchDown("attack");
  input.touchUp("attack");
  expect(actions.releaseAttack).toHaveBeenCalledTimes(1);
  input.dispose();
  arena.dispatchEvent(new KeyboardEvent("keydown", { code: "KeyD" }));
  expect(input.controls.right).toBe(false);
});
