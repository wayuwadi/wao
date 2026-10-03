/** Original short synthesized tones. No context until a user enables sound. */
export class BeastAudio {
  muted = true;
  private context?: AudioContext;
  private wanted = false;
  private voices = new Set<OscillatorNode>();
  async unlock() {
    if (this.muted) return;
    this.wanted = true;
    try {
      this.context ??= new AudioContext();
      const c = this.context;
      if (c.state === "suspended") await c.resume();
      if (!this.wanted && c.state !== "closed") await c.suspend();
    } catch {
      this.wanted = false;
    }
  }
  play(kind: "hit" | "heal" | "win" | "lose" | "choose") {
    const c = this.context;
    if (!c || c.state !== "running" || this.muted || !this.wanted) return;
    const tone = { hit: 190, heal: 640, win: 880, lose: 160, choose: 410 }[
        kind
      ],
      length = kind === "win" ? 0.3 : 0.13;
    const voice = c.createOscillator(),
      gain = c.createGain();
    voice.type = kind === "hit" ? "triangle" : "sine";
    voice.frequency.setValueAtTime(tone, c.currentTime);
    voice.frequency.exponentialRampToValueAtTime(
      tone * 1.3,
      c.currentTime + length,
    );
    gain.gain.setValueAtTime(0.05, c.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, c.currentTime + length);
    voice.connect(gain).connect(c.destination);
    voice.start();
    voice.stop(c.currentTime + length);
    this.voices.add(voice);
    voice.onended = () => {
      this.voices.delete(voice);
      voice.disconnect();
      gain.disconnect();
    };
  }
  stop() {
    this.wanted = false;
    for (const voice of this.voices) {
      try {
        voice.stop();
      } catch {
        /* already stopped */
      }
    }
    this.voices.clear();
    void this.context?.suspend();
  }
  dispose() {
    this.stop();
    void this.context?.close();
    this.context = undefined;
  }
}
