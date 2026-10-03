import { afterEach, expect, it, vi } from "vitest";
import { RiftAudio } from "./audio";

afterEach(() => vi.unstubAllGlobals());

function fixture() {
  const oscillator = {
    type: "",
    frequency: {
      setValueAtTime: vi.fn(),
      exponentialRampToValueAtTime: vi.fn(),
    },
    connect: vi.fn(),
    start: vi.fn(),
    stop: vi.fn(),
    disconnect: vi.fn(),
    onended: null as (() => void) | null,
  };
  const gain = {
    gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() },
    connect: vi.fn(),
    disconnect: vi.fn(),
  };
  oscillator.connect.mockReturnValue(gain);
  const context = {
    state: "suspended",
    currentTime: 0,
    destination: {},
    createOscillator: vi.fn(() => oscillator),
    createGain: vi.fn(() => gain),
    resume: vi.fn(async () => {
      context.state = "running";
    }),
    suspend: vi.fn(async () => {
      context.state = "suspended";
    }),
    close: vi.fn(async () => {
      context.state = "closed";
    }),
  };
  const constructor = vi.fn(function () {
    return context;
  });
  vi.stubGlobal("AudioContext", constructor);
  return { context, constructor, oscillator, gain };
}

it("默认静音不创建上下文；用户解锁后播放，暂停清除全部声音并在卸载关闭", async () => {
  const f = fixture(),
    audio = new RiftAudio();
  await audio.unlock();
  audio.play("hit");
  expect(f.constructor).not.toHaveBeenCalled();
  audio.muted = false;
  await audio.unlock();
  audio.play("shot");
  expect(f.oscillator.start).toHaveBeenCalledOnce();
  audio.stop();
  expect(f.oscillator.stop).toHaveBeenCalledTimes(2);
  expect(f.context.state).toBe("suspended");
  audio.play("hit");
  expect(f.oscillator.start).toHaveBeenCalledOnce();
  f.oscillator.onended?.();
  expect(f.gain.disconnect).toHaveBeenCalledOnce();
  await audio.unlock();
  audio.muted = true;
  audio.play("jump");
  expect(f.oscillator.start).toHaveBeenCalledOnce();
  audio.dispose();
  expect(f.context.state).toBe("closed");
});

it("延迟的音频解锁不能撤销暂停；音频被浏览器拒绝也不阻止继续游戏", async () => {
  const f = fixture(),
    audio = new RiftAudio();
  let complete!: () => void;
  f.context.resume.mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        complete = () => {
          f.context.state = "running";
          resolve();
        };
      }),
  );
  audio.muted = false;
  const pending = audio.unlock();
  audio.stop();
  complete();
  await pending;
  expect(f.context.state).toBe("suspended");
  audio.play("shot");
  expect(f.oscillator.start).not.toHaveBeenCalled();
  f.context.resume.mockRejectedValueOnce(new Error("NotAllowedError"));
  await expect(audio.unlock()).resolves.toBeUndefined();
  expect(f.oscillator.start).not.toHaveBeenCalled();
});
