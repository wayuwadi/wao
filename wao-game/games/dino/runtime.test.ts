// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { DinoInput } from "./input";
import { DinoLoop } from "./loop";
import { childEvent, envelope, parentCommand } from "../../protocol/dino";
import { freshMemory } from "./save";
const disposers: (() => void)[] = [];
afterEach(() => {
  disposers.splice(0).forEach((fn) => fn());
  document.body.replaceChildren();
  vi.restoreAllMocks();
});
function controls() {
  const arena = document.createElement("section");
  arena.tabIndex = 0;
  document.body.append(arena);
  const jump = vi.fn(),
    pause = vi.fn(),
    restart = vi.fn(),
    input = new DinoInput(arena, () => true, jump, pause, restart);
  disposers.push(() => input.dispose());
  return { arena, input, jump, pause };
}
function key(target: EventTarget, type: string, code: string, repeat = false) {
  const e = new KeyboardEvent(type, {
    code,
    repeat,
    cancelable: true,
    bubbles: true,
  });
  target.dispatchEvent(e);
  return e;
}
describe("输入所有权", () => {
  it("只有舞台空格被处理，其他页面与按钮快捷键不被劫持", () => {
    const { arena, jump } = controls(),
      textbox = document.createElement("input"),
      button = document.createElement("button");
    arena.append(button);
    document.body.append(textbox);
    expect(key(textbox, "keydown", "Space").defaultPrevented).toBe(false);
    expect(key(button, "keydown", "Space").defaultPrevented).toBe(false);
    expect(key(window, "keydown", "Space").defaultPrevented).toBe(false);
    expect(jump).not.toHaveBeenCalled();
    expect(key(arena, "keydown", "Space").defaultPrevented).toBe(true);
    key(arena, "keydown", "Space", true);
    expect(jump).toHaveBeenCalledTimes(1);
  });
  it("失焦清空键盘和触控状态并暂停，销毁不留监听", () => {
    const { arena, input, jump, pause } = controls();
    key(arena, "keydown", "ArrowUp");
    input.toggleDuck();
    expect(input.state).toEqual({ jumpHeld: true, duck: true });
    window.dispatchEvent(new Event("blur"));
    expect(input.state).toEqual({ jumpHeld: false, duck: false });
    expect(pause).toHaveBeenCalledOnce();
    input.dispose();
    key(arena, "keydown", "Space");
    window.dispatchEvent(new Event("blur"));
    expect(jump).toHaveBeenCalledOnce();
    expect(pause).toHaveBeenCalledOnce();
  });
  it("后台事件清理键盘与触控，销毁后不再响应", () => {
    const { arena, input, pause } = controls();
    const hidden = vi.spyOn(document, "hidden", "get");
    hidden.mockReturnValue(false);
    key(arena, "keydown", "ArrowUp");
    input.toggleDuck();
    document.dispatchEvent(new Event("visibilitychange"));
    expect(pause).not.toHaveBeenCalled();
    hidden.mockReturnValue(true);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(input.state).toEqual({ jumpHeld: false, duck: false });
    expect(pause).toHaveBeenCalledOnce();
    input.dispose();
    document.dispatchEvent(new Event("visibilitychange"));
    expect(pause).toHaveBeenCalledOnce();
    hidden.mockRestore();
  });
  it("移到外部才暂停，触屏跳跃释放后不持续跳", () => {
    const { arena, input, pause } = controls(),
      child = document.createElement("button");
    arena.append(child);
    arena.dispatchEvent(new FocusEvent("focusout", { relatedTarget: child }));
    expect(pause).not.toHaveBeenCalled();
    child.dispatchEvent(
      new FocusEvent("focusout", { relatedTarget: null, bubbles: true }),
    );
    expect(pause).not.toHaveBeenCalled();
    input.pressJump();
    expect(input.state.jumpHeld).toBe(true);
    input.releaseJump();
    expect(input.state.jumpHeld).toBe(false);
    arena.dispatchEvent(
      new FocusEvent("focusout", { relatedTarget: document.body }),
    );
    expect(pause).toHaveBeenCalledOnce();
  });
});
describe("动画生命周期", () => {
  it("停止即取消帧，恢复丢弃长间隔；结束后不继续申请帧", () => {
    let tick: FrameRequestCallback = () => {},
      id = 0;
    const request = vi.fn((fn: FrameRequestCallback) => {
        tick = fn;
        return ++id;
      }),
      cancel = vi.fn();
    const update = vi.fn(() => true),
      render = vi.fn(),
      loop = new DinoLoop(update, render, { request, cancel });
    loop.start();
    tick(0);
    tick(16.7);
    expect(update).toHaveBeenCalledTimes(2);
    loop.stop();
    expect(cancel).toHaveBeenCalledOnce();
    loop.start();
    tick(100_000);
    expect(update).toHaveBeenCalledTimes(2);
    update.mockReturnValue(false);
    tick(100_017);
    const count = request.mock.calls.length;
    tick(100_033);
    expect(request).toHaveBeenCalledTimes(count);
    expect(update).toHaveBeenCalledTimes(3);
  });
});
describe("iframe 协议 schema", () => {
  const id = "11111111-2222-3333-4444-555555555555";
  it("限定封套/游戏/版本/实例/序号和所有负载字段", () => {
    const msg = envelope(
      {
        type: "init",
        payload: { memory: freshMemory(), theme: "light", mode: "local" },
      },
      id,
      1,
    );
    expect(parentCommand(msg, id)).toBe(true);
    for (const bad of [
      { ...msg, protocol: 2 },
      { ...msg, gameId: "rift" },
      { ...msg, seq: -1 },
      { ...msg, extra: 1 },
      { ...msg, payload: { ...msg.payload, script: "x" } },
    ])
      expect(parentCommand(bad, id)).toBe(false);
    expect(parentCommand(msg, "different-instance")).toBe(false);
    expect(
      childEvent(
        envelope({ type: "checkpoint", payload: freshMemory() }, id, 2),
        id,
      ),
    ).toBe(true);
    expect(
      childEvent(
        envelope(
          { type: "snapshot-result", payload: { ...freshMemory(), runs: -1 } },
          id,
          3,
        ),
        id,
      ),
    ).toBe(false);
  });
});
