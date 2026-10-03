import { STEP } from "./engine";

export type Clock = {
  request: (fn: FrameRequestCallback) => number;
  cancel: (id: number) => void;
};
const browserClock: Clock = {
  request: (fn) => requestAnimationFrame(fn),
  cancel: (id) => cancelAnimationFrame(id),
};

/** 丢弃后台积压时间；渲染帧与 120Hz 物理分离，停止后没有空转帧。 */
export class DinoLoop {
  private frame: number | null = null;
  private previous: number | null = null;
  private accumulator = 0;
  private active = false;
  constructor(
    private update: () => boolean,
    private render: () => void,
    private clock = browserClock,
  ) {}
  start() {
    if (this.active) return;
    this.active = true;
    this.previous = null;
    this.accumulator = 0;
    this.frame = this.clock.request(this.tick);
  }
  stop() {
    this.active = false;
    if (this.frame !== null) this.clock.cancel(this.frame);
    this.frame = null;
    this.previous = null;
    this.accumulator = 0;
  }
  private tick = (time: number) => {
    this.frame = null;
    if (!this.active) return;
    if (this.previous !== null)
      this.accumulator += Math.min(
        0.05,
        Math.max(0, (time - this.previous) / 1000),
      );
    this.previous = time;
    while (this.accumulator >= STEP) {
      this.accumulator -= STEP;
      if (!this.update()) {
        this.stop();
        break;
      }
    }
    this.render();
    if (this.active) this.frame = this.clock.request(this.tick);
  };
}
