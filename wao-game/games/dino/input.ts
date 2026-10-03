import type { Controls } from "./engine";

/** 键盘只监听舞台，不监听全局 keydown；释放及失焦始终清掉状态。 */
export class DinoInput {
  readonly state: Controls = { jumpHeld: false, duck: false };
  private keys = new Set<string>();
  private touchJump = false;
  private touchDuck = false;
  private controller = new AbortController();
  constructor(
    private arena: HTMLElement,
    private active: () => boolean,
    private jump: () => void,
    private pause: () => void,
    private restart: () => void,
  ) {
    const signal = this.controller.signal;
    arena.addEventListener("keydown", this.down, { signal });
    window.addEventListener("keyup", this.up, { signal });
    window.addEventListener("blur", this.lostFocus, { signal });
    document.addEventListener("visibilitychange", this.visibility, { signal });
    arena.addEventListener("focusout", this.focusOut, { signal });
  }
  private down = (e: KeyboardEvent) => {
    if (e.target !== this.arena) return;
    if (e.code === "Escape" || e.code === "KeyP") {
      e.preventDefault();
      this.lostFocus();
      return;
    }
    if (e.code === "KeyR") {
      if (!e.repeat) this.restart();
      return;
    }
    if (!this.active() || !["Space", "ArrowUp", "ArrowDown"].includes(e.code))
      return;
    e.preventDefault();
    if (e.repeat || this.keys.has(e.code)) return;
    this.keys.add(e.code);
    this.sync();
    if (e.code !== "ArrowDown") this.jump();
  };
  private up = (e: KeyboardEvent) => {
    this.keys.delete(e.code);
    this.sync();
  };
  private visibility = () => {
    if (document.hidden) this.lostFocus();
  };
  private focusOut = (e: FocusEvent) => {
    // 开始/继续按钮卸载会冒泡 focusout；舞台本身失焦才中断运行。
    if (e.target !== this.arena) return;
    if (!this.arena.contains(e.relatedTarget as Node | null)) this.lostFocus();
  };
  private lostFocus = () => {
    this.clear();
    this.pause();
  };
  private sync() {
    this.state.jumpHeld =
      this.touchJump || this.keys.has("Space") || this.keys.has("ArrowUp");
    this.state.duck = this.touchDuck || this.keys.has("ArrowDown");
  }
  pressJump() {
    if (!this.active()) return;
    this.touchJump = true;
    this.sync();
    this.jump();
  }
  releaseJump() {
    this.touchJump = false;
    this.sync();
  }
  toggleDuck() {
    if (!this.active()) return false;
    this.touchDuck = !this.touchDuck;
    this.sync();
    return this.touchDuck;
  }
  clear() {
    this.keys.clear();
    this.touchJump = false;
    this.touchDuck = false;
    this.sync();
  }
  dispose() {
    this.clear();
    this.controller.abort();
  }
}
