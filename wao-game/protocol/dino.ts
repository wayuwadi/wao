import { isDinoMemory, type DinoMemory } from "../games/dino/save";

export type ParentCommand =
  | {
      type: "init";
      payload: {
        memory: DinoMemory;
        theme: "light" | "dark";
        mode: "local" | "session";
      };
    }
  | { type: "pause"; payload: { reason: string } }
  | { type: "resume" | "dispose" | "snapshot-request"; payload: null }
  | { type: "theme"; payload: { theme: "light" | "dark" } }
  | { type: "viewport"; payload: { width: number; height: number } }
  | { type: "audio"; payload: { muted: boolean } };
export type ChildEvent =
  | { type: "ready"; payload: { rulesVersion: 1 } }
  | {
      type: "state";
      payload: { phase: "ready" | "running" | "paused" | "crashed" | "won" };
    }
  | { type: "checkpoint" | "snapshot-result"; payload: DinoMemory }
  | { type: "disposed"; payload: null };
export type Envelope<T> = T & {
  channel: "wao-game";
  protocol: 1;
  gameId: "dino";
  instanceId: string;
  seq: number;
};
function object(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}
function fields(v: Record<string, unknown>, names: string[]) {
  return (
    Object.keys(v).length === names.length &&
    names.every((n) => Object.prototype.hasOwnProperty.call(v, n))
  );
}
function base(
  value: unknown,
  id: string,
): value is Envelope<{ type: string; payload: unknown }> {
  if (
    !object(value) ||
    !fields(value, [
      "channel",
      "protocol",
      "gameId",
      "instanceId",
      "seq",
      "type",
      "payload",
    ])
  )
    return false;
  return (
    value.channel === "wao-game" &&
    value.protocol === 1 &&
    value.gameId === "dino" &&
    value.instanceId === id &&
    /^[a-zA-Z0-9-]{16,64}$/.test(id) &&
    Number.isSafeInteger(value.seq) &&
    (value.seq as number) > 0 &&
    typeof value.type === "string"
  );
}
export function parentCommand(
  v: unknown,
  id: string,
): v is Envelope<ParentCommand> {
  if (!base(v, id)) return false;
  const p = v.payload;
  if (["resume", "dispose", "snapshot-request"].includes(v.type))
    return p === null;
  if (!object(p)) return false;
  if (v.type === "viewport")
    return (
      fields(p, ["width", "height"]) &&
      Number.isFinite(p.width) &&
      Number.isFinite(p.height) &&
      (p.width as number) >= 200 &&
      (p.width as number) <= 10000 &&
      (p.height as number) >= 200 &&
      (p.height as number) <= 10000
    );
  if (v.type === "pause")
    return (
      fields(p, ["reason"]) &&
      typeof p.reason === "string" &&
      p.reason.length <= 80
    );
  if (v.type === "audio")
    return fields(p, ["muted"]) && typeof p.muted === "boolean";
  if (v.type === "theme")
    return (
      fields(p, ["theme"]) && ["light", "dark"].includes(p.theme as string)
    );
  return (
    v.type === "init" &&
    fields(p, ["memory", "theme", "mode"]) &&
    isDinoMemory(p.memory) &&
    ["light", "dark"].includes(p.theme as string) &&
    ["local", "session"].includes(p.mode as string)
  );
}
export function childEvent(v: unknown, id: string): v is Envelope<ChildEvent> {
  if (!base(v, id)) return false;
  const p = v.payload;
  if (v.type === "disposed") return p === null;
  if (v.type === "checkpoint" || v.type === "snapshot-result")
    return isDinoMemory(p);
  if (!object(p)) return false;
  if (v.type === "ready")
    return fields(p, ["rulesVersion"]) && p.rulesVersion === 1;
  return (
    v.type === "state" &&
    fields(p, ["phase"]) &&
    ["ready", "running", "paused", "crashed", "won"].includes(p.phase as string)
  );
}
export function envelope<T extends ParentCommand | ChildEvent>(
  event: T,
  instanceId: string,
  seq: number,
): Envelope<T> {
  return {
    ...event,
    channel: "wao-game",
    protocol: 1,
    gameId: "dino",
    instanceId,
    seq,
  };
}
