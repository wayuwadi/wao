import { distance, type Run } from "./engine";

export type DinoMemory = {
  best: number;
  runs: number;
  totalDistance: number;
  sprintWins: number;
  bestSprintMs: number | null;
  practiceComplete: boolean;
  discoveries: number;
  muted: boolean;
};
export const freshMemory = (): DinoMemory => ({
  best: 0,
  runs: 0,
  totalDistance: 0,
  sprintWins: 0,
  bestSprintMs: null,
  practiceComplete: false,
  discoveries: 0,
  muted: true,
});
const keys = Object.keys(freshMemory());
const integer = (v: unknown, max: number) =>
  typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= max;
export function isDinoMemory(value: unknown): value is DinoMemory {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  return (
    Object.keys(v).length === keys.length &&
    keys.every((k) => Object.prototype.hasOwnProperty.call(v, k)) &&
    integer(v.best, 100_000_000) &&
    integer(v.runs, 1_000_000_000) &&
    integer(v.totalDistance, 1_000_000_000_000) &&
    integer(v.sprintWins, 1_000_000_000) &&
    (v.bestSprintMs === null ||
      (integer(v.bestSprintMs, 3_600_000) &&
        (v.bestSprintMs as number) >= 20_000)) &&
    typeof v.practiceComplete === "boolean" &&
    integer(v.discoveries, 7) &&
    typeof v.muted === "boolean" &&
    (v.sprintWins as number) <= (v.runs as number) &&
    (v.best as number) <= (v.totalDistance as number) &&
    ((v.sprintWins === 0 && v.bestSprintMs === null) ||
      ((v.sprintWins as number) > 0 && v.bestSprintMs !== null))
  );
}
export function finishMemory(previous: DinoMemory, run: Run): DinoMemory {
  if (run.phase !== "won" && run.phase !== "crashed") return { ...previous };
  if (run.mode === "practice")
    return {
      ...previous,
      practiceComplete: previous.practiceComplete || run.phase === "won",
    };
  const metres = Math.min(100_000_000, distance(run)),
    win = run.mode === "sprint" && run.phase === "won";
  const ms = Math.round(run.elapsed * 1000);
  return {
    ...previous,
    best: Math.max(previous.best, metres),
    runs: Math.min(1_000_000_000, previous.runs + 1),
    totalDistance: Math.min(1_000_000_000_000, previous.totalDistance + metres),
    sprintWins: Math.min(1_000_000_000, previous.sprintWins + Number(win)),
    bestSprintMs: win
      ? Math.min(previous.bestSprintMs ?? Infinity, ms)
      : previous.bestSprintMs,
    discoveries:
      previous.discoveries |
      (metres >= 1 ? 1 : 0) |
      (metres >= 90 ? 2 : 0) |
      (metres >= 240 ? 4 : 0),
  };
}
