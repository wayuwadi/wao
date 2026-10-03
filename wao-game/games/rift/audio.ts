/** Original short oscillator sounds; no downloaded sound assets. */
export class RiftAudio {
  private context?: AudioContext;
  private voices = new Set<OscillatorNode>();
  private wanted = false;
  muted = true;
  async unlock() {
    if (this.muted) return;
    this.wanted = true;
    try {
      this.context ??= new AudioContext();
      const context = this.context;
      if (context.state === "suspended") await context.resume();
      // A delayed browser resume must not undo a pause/dispose requested meanwhile.
      if (!this.wanted && context.state !== "closed") await context.suspend();
    } catch {
      // Audio can be unavailable or blocked; gameplay must still start.
      this.wanted = false;
    }
  }
  play(
    kind:
      "swing" | "shot" | "jump" | "dodge" | "skill" | "hit" | "win" | "lose",
  ) {
    const c = this.context;
    if (this.muted || !this.wanted || !c || c.state !== "running") return;
    const pairs = {
      swing: [360, 160],
      shot: [740, 290],
      jump: [240, 540],
      dodge: [520, 180],
      skill: [330, 880],
      hit: [130, 65],
      win: [520, 1040],
      lose: [220, 90],
    };
    const tones = pairs[kind],
      voice = c.createOscillator(),
      gain = c.createGain(),
      length = kind === "win" || kind === "lose" ? 0.34 : 0.11;
    voice.type = kind === "hit" ? "triangle" : "sine";
    voice.frequency.setValueAtTime(tones[0]!, c.currentTime);
    voice.frequency.exponentialRampToValueAtTime(
      tones[1]!,
      c.currentTime + length,
    );
    gain.gain.setValueAtTime(0.065, c.currentTime);
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
        /* already ended */
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
