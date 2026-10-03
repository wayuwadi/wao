import * as dino from "./dino";
import * as rift from "./rift";
import * as beasts from "./beasts";
import * as camp from "./camp";
import * as starfield from "./starfield";
import type { GameId, GameMemories } from "../host/registry";
export type ParentCommand =
  | Exclude<dino.ParentCommand, { type: "init" }>
  | {
      type: "init";
      payload: {
        memory: GameMemories[GameId];
        theme: "light" | "dark";
        mode: "local" | "session";
      };
    };
export function childEvent(
  value: unknown,
  instance: string,
  gameId: GameId,
): value is
  | dino.Envelope<dino.ChildEvent>
  | rift.Envelope<rift.ChildEvent>
  | starfield.Envelope<starfield.ChildEvent>
  | beasts.Envelope<beasts.ChildEvent>
  | camp.Envelope<camp.ChildEvent> {
  return gameId === "dino"
    ? dino.childEvent(value, instance)
    : gameId === "rift"
      ? rift.childEvent(value, instance)
      : gameId === "starfield"
        ? starfield.childEvent(value, instance)
        : gameId === "beasts"
          ? beasts.childEvent(value, instance)
          : camp.childEvent(value, instance);
}
export function envelope(
  event: ParentCommand,
  instanceId: string,
  seq: number,
  gameId: GameId,
) {
  return {
    ...event,
    channel: "wao-game",
    protocol: 1,
    gameId,
    instanceId,
    seq,
  };
}
