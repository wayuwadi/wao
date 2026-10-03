import type { RiftControls } from "./engine";
export interface InputActions {
  active(): boolean;
  turn(direction: -1 | 1): void;
  attack(): void;
  releaseAttack(): void;
  jump(): void;
  dodge(direction: number): void;
  skill(slot: number): void;
  pause(): void;
  restart(): void;
}
export class RiftInput {
  readonly controls: RiftControls = {
    left: false,
    right: false,
    down: false,
    attackHeld: false,
  };
  private keys = new Set<string>();
  private touches = new Set<string>();
  private controller = new AbortController();
  constructor(
    private arena: HTMLElement,
    private actions: InputActions,
  ) {
    const signal = this.controller.signal;
    arena.addEventListener("keydown", this.down, { signal });
    arena.addEventListener("focusout", this.focusOut, { signal });
    window.addEventListener("keyup", this.up, { signal });
    window.addEventListener("blur", this.lost, { signal });
    document.addEventListener("visibilitychange", this.visibility, { signal });
  }
  private synchronize() {
    this.controls.left =
      this.keys.has("KeyA") ||
      this.keys.has("ArrowLeft") ||
      this.touches.has("left");
    this.controls.right =
      this.keys.has("KeyD") ||
      this.keys.has("ArrowRight") ||
      this.touches.has("right");
    this.controls.down =
      this.keys.has("KeyS") ||
      this.keys.has("ArrowDown") ||
      this.touches.has("down");
    this.controls.attackHeld =
      this.keys.has("KeyJ") || this.touches.has("attack");
  }
  private down = (e: KeyboardEvent) => {
    if (e.target !== this.arena) return;
    if (["KeyP", "Escape"].includes(e.code)) {
      e.preventDefault();
      this.lost();
      return;
    }
    if (e.code === "KeyR" && !e.repeat && !this.actions.active()) {
      e.preventDefault();
      this.actions.restart();
      return;
    }
    const allowed = [
      "KeyA",
      "KeyD",
      "KeyS",
      "KeyW",
      "ArrowLeft",
      "ArrowRight",
      "ArrowDown",
      "ArrowUp",
      "Space",
      "KeyJ",
      "KeyK",
      "KeyL",
      "KeyU",
      "ShiftLeft",
      "ShiftRight",
    ];
    if (!allowed.includes(e.code) || !this.actions.active()) return;
    e.preventDefault();
    if (e.repeat || this.keys.has(e.code)) return;
    this.keys.add(e.code);
    this.synchronize();
    // A short direction tap must turn even if keyup precedes the next frame.
    if (["KeyA", "ArrowLeft"].includes(e.code)) this.actions.turn(-1);
    if (["KeyD", "ArrowRight"].includes(e.code)) this.actions.turn(1);
    if (["Space", "KeyW", "ArrowUp"].includes(e.code)) this.actions.jump();
    if (e.code === "KeyJ") this.actions.attack();
    if (e.code.startsWith("Shift"))
      this.actions.dodge(
        Number(this.controls.right) - Number(this.controls.left),
      );
    const slot = ["KeyK", "KeyL", "KeyU"].indexOf(e.code);
    if (slot >= 0) this.actions.skill(slot);
  };
  private up = (e: KeyboardEvent) => {
    const held = this.keys.has(e.code);
    this.keys.delete(e.code);
    this.synchronize();
    if (held && e.code === "KeyJ" && this.actions.active())
      this.actions.releaseAttack();
  };
  private focusOut = (e: FocusEvent) => {
    if (
      e.target === this.arena &&
      !this.arena.contains(e.relatedTarget as Node | null)
    )
      this.lost();
  };
  private visibility = () => {
    if (document.hidden) this.lost();
  };
  private lost = () => {
    this.clear();
    this.actions.pause();
  };
  touchDown(action: string) {
    if (!this.actions.active()) return;
    this.arena.focus({ preventScroll: true });
    this.touches.add(action);
    this.synchronize();
    if (action === "left") this.actions.turn(-1);
    if (action === "right") this.actions.turn(1);
    if (action === "attack") this.actions.attack();
    if (action === "jump") this.actions.jump();
    if (action === "dodge")
      this.actions.dodge(
        Number(this.controls.right) - Number(this.controls.left),
      );
    if (action.startsWith("skill"))
      this.actions.skill(Number(action.slice(-1)));
  }
  touchUp(action: string, cancelled = false) {
    const held = this.touches.delete(action);
    this.synchronize();
    if (held && action === "attack" && !cancelled && this.actions.active())
      this.actions.releaseAttack();
    if (cancelled) this.lost();
  }
  clear() {
    this.keys.clear();
    this.touches.clear();
    this.synchronize();
  }
  dispose() {
    this.clear();
    this.controller.abort();
  }
}
