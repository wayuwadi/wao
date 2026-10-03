/** 原创合成提示音：无网络素材，默认静音，仅用户手势创建音频上下文。 */
export class DinoAudio {
  private context?: AudioContext;
  private voices = new Set<OscillatorNode>();
  muted = true;
  async unlock() {
    if (this.muted) return;
    try {
      this.context ??= new AudioContext();
      if (this.context.state === "suspended") await this.context.resume();
    } catch {
      /* 不支持音频时不影响玩法。 */
    }
  }
  play(kind: "jump" | "land" | "hit" | "win" | "pass") {
    const ctx = this.context;
    if (this.muted || !ctx || ctx.state !== "running") return;
    const oscillator = ctx.createOscillator(),
      gain = ctx.createGain();
    const start =
      kind === "jump"
        ? 330
        : kind === "hit"
          ? 160
          : kind === "win"
            ? 520
            : kind === "pass"
              ? 700
              : 100;
    oscillator.type = kind === "hit" ? "triangle" : "sine";
    oscillator.frequency.setValueAtTime(start, ctx.currentTime);
    oscillator.frequency.exponentialRampToValueAtTime(
      kind === "jump" || kind === "win" ? start * 1.6 : start / 2,
      ctx.currentTime + 0.13,
    );
    gain.gain.setValueAtTime(0.075, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.16);
    oscillator.connect(gain).connect(ctx.destination);
    this.voices.add(oscillator);
    oscillator.onended = () => {
      this.voices.delete(oscillator);
      oscillator.disconnect();
      gain.disconnect();
    };
    oscillator.start();
    oscillator.stop(ctx.currentTime + 0.18);
  }
  stop() {
    for (const voice of this.voices) {
      try {
        voice.stop();
      } catch {
        /* 已停止。 */
      }
    }
    this.voices.clear();
    void this.context?.suspend().catch(() => {});
  }
  dispose() {
    this.stop();
    void this.context?.close().catch(() => {});
    this.context = undefined;
  }
}
