import { isDinoMemory, type DinoMemory } from "../games/dino/save";

export type GameId = "dino";
export type MemoryRecord = {
  gameId: GameId;
  rulesVersion: 1;
  savedAt: string;
  revision: number;
  payload: DinoMemory;
};
export type MemoryFile = {
  format: "wao-game-memory";
  formatVersion: 1;
  exportedAt: string;
  games: MemoryRecord[];
};
export type Backup = { id: string; original: MemoryRecord; backedUpAt: string };
export const SINGLE_LIMIT = 2 * 1024 * 1024,
  FILE_LIMIT = 10 * 1024 * 1024;
export const DATABASE = "wao-game-memory"; // 稳定命名空间，不包含账号、资源路径或部署版本。
export class MemoryError extends Error {
  constructor(
    public readonly kind: "invalid" | "version" | "conflict" | "storage",
    message: string,
  ) {
    super(message);
  }
}
function object(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}
function fields(v: Record<string, unknown>, keys: string[]) {
  return (
    keys.length === Object.keys(v).length &&
    keys.every((k) => Object.prototype.hasOwnProperty.call(v, k))
  );
}
function date(v: unknown) {
  return (
    typeof v === "string" &&
    /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(v) &&
    Number.isFinite(Date.parse(v)) &&
    new Date(v).toISOString() === v
  );
}
function fail(
  message = "记忆结构或数值不正确，请重新选择原始导出文件。",
): never {
  throw new MemoryError("invalid", message);
}
export function validateRecord(v: unknown): MemoryRecord {
  if (
    !object(v) ||
    !fields(v, ["gameId", "rulesVersion", "savedAt", "revision", "payload"])
  )
    fail();
  if (v.gameId !== "dino")
    throw new MemoryError(
      "version",
      "该游戏记忆尚不受当前版本支持，请使用对应正式版本。",
    );
  if (v.rulesVersion !== 1)
    throw new MemoryError(
      "version",
      "游戏规则版本不兼容。原进度未修改，请保留文件并更新页面后重试。",
    );
  if (
    !date(v.savedAt) ||
    !Number.isSafeInteger(v.revision) ||
    (v.revision as number) < 0 ||
    !isDinoMemory(v.payload)
  )
    fail();
  return {
    gameId: "dino",
    rulesVersion: 1,
    savedAt: v.savedAt as string,
    revision: v.revision as number,
    payload: { ...v.payload },
  };
}
export function parseMemory(text: string): MemoryFile {
  if (new TextEncoder().encode(text).byteLength > FILE_LIMIT)
    fail("文件超过 10 MiB，未读取或覆盖任何进度。");
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    fail("文件不是完整的 JSON 记忆，请重新导出或选择备份。");
  }
  if (
    !object(value) ||
    !fields(value, ["format", "formatVersion", "exportedAt", "games"]) ||
    value.format !== "wao-game-memory"
  )
    fail();
  if (value.formatVersion !== 1)
    throw new MemoryError(
      "version",
      "记忆文件版本不兼容，请保留文件并更新页面后重试。",
    );
  if (
    !date(value.exportedAt) ||
    !Array.isArray(value.games) ||
    value.games.length < 1 ||
    value.games.length > 7
  )
    fail();
  const seen = new Set<string>();
  const games = value.games.map((game) => {
    if (
      new TextEncoder().encode(JSON.stringify(game)).byteLength > SINGLE_LIMIT
    )
      fail("单款记忆超过 2 MiB。");
    const record = validateRecord(game);
    if (seen.has(record.gameId)) fail("文件内包含重复游戏，未导入。");
    seen.add(record.gameId);
    return record;
  });
  return {
    format: "wao-game-memory",
    formatVersion: 1,
    exportedAt: value.exportedAt as string,
    games,
  };
}
export function exportMemory(games: MemoryRecord[]): string {
  const file: MemoryFile = {
    format: "wao-game-memory",
    formatVersion: 1,
    exportedAt: new Date().toISOString(),
    games: games.map(validateRecord),
  };
  const text = JSON.stringify(file, null, 2);
  parseMemory(text);
  return text;
}
export function summary(record: MemoryRecord) {
  const p = record.payload;
  return `最远 ${p.best} 米 · ${p.runs} 局 · 千米通关 ${p.sprintWins} 次${p.practiceComplete ? " · 已完成练习" : ""}`;
}
function storageError(error: unknown) {
  if (error instanceof MemoryError) return error;
  const quota =
    error instanceof DOMException && error.name === "QuotaExceededError";
  return new MemoryError(
    "storage",
    quota
      ? "本机存储空间不足。进度仍在本页，请先导出记忆，再释放空间并重试。"
      : "本机存储暂不可用。进度仍在本页，请重试或导出记忆。",
  );
}

/** 所有写入以同一个 IndexedDB 事务比较 revision；BroadcastChannel 不承担互斥。 */
export class MemoryStore {
  private connection: Promise<IDBDatabase> | undefined;
  constructor(
    private factory: IDBFactory = indexedDB,
    private name = DATABASE,
  ) {}
  private open() {
    this.connection ??= new Promise<IDBDatabase>((resolve, reject) => {
      let req: IDBOpenDBRequest;
      try {
        req = this.factory.open(this.name, 1);
      } catch (e) {
        reject(storageError(e));
        return;
      }
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("games"))
          db.createObjectStore("games", { keyPath: "gameId" });
        if (!db.objectStoreNames.contains("backups"))
          db.createObjectStore("backups", { keyPath: "id" });
      };
      req.onerror = () => reject(storageError(req.error));
      req.onblocked = () =>
        reject(
          new MemoryError(
            "storage",
            "另一个旧页面阻止打开游戏记忆，请关闭旧页面后重试或先导出。",
          ),
        );
      req.onsuccess = () => {
        req.result.onversionchange = () => {
          req.result.close();
          this.connection = undefined;
        };
        resolve(req.result);
      };
    }).catch((e) => {
      this.connection = undefined;
      throw storageError(e);
    });
    return this.connection;
  }
  async all(): Promise<MemoryRecord[]> {
    try {
      const db = await this.open();
      return await new Promise((resolve, reject) => {
        const tx = db.transaction("games"),
          request = tx.objectStore("games").getAll();
        tx.onabort = () => reject(storageError(tx.error));
        request.onsuccess = () => {
          try {
            resolve(request.result.map(validateRecord));
          } catch (e) {
            reject(e);
          }
        };
      });
    } catch (e) {
      throw storageError(e);
    }
  }
  async backups(): Promise<Backup[]> {
    try {
      const db = await this.open();
      return await new Promise((resolve, reject) => {
        const tx = db.transaction("backups"),
          request = tx.objectStore("backups").getAll();
        tx.onabort = () => reject(storageError(tx.error));
        request.onsuccess = () => {
          try {
            resolve(
              request.result
                .map((b: Backup) => ({
                  ...b,
                  original: validateRecord(b.original),
                }))
                .sort((a, b) => b.backedUpAt.localeCompare(a.backedUpAt)),
            );
          } catch (e) {
            reject(e);
          }
        };
      });
    } catch (e) {
      throw storageError(e);
    }
  }
  async save(payload: DinoMemory, expectedRevision: number) {
    const input: MemoryRecord = {
      gameId: "dino",
      rulesVersion: 1,
      revision: 0,
      savedAt: new Date().toISOString(),
      payload,
    };
    return (await this.replace([input], { dino: expectedRevision }, false))[0]!;
  }
  /** 读取另一页前先持久化本页草稿；备份失败时不丢弃草稿。 */
  async readWithBackup(
    draft: MemoryRecord,
    expectedRevision: number,
  ): Promise<MemoryRecord | undefined> {
    const original = validateRecord(draft);
    try {
      const db = await this.open();
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(["games", "backups"], "readwrite");
        let result: MemoryRecord | undefined, error: unknown;
        tx.onabort = () => reject(storageError(error ?? tx.error));
        tx.oncomplete = () => resolve(result);
        const request = tx.objectStore("games").get("dino");
        request.onsuccess = () => {
          try {
            result = request.result
              ? validateRecord(request.result)
              : undefined;
            if ((result?.revision ?? 0) !== expectedRevision)
              throw new MemoryError(
                "conflict",
                "另一页再次更新了记忆，请重新预览。本页草稿未丢弃。",
              );
            tx.objectStore("backups").add({
              id: crypto.randomUUID(),
              original,
              backedUpAt: new Date().toISOString(),
            } satisfies Backup);
          } catch (e) {
            error = e;
            tx.abort();
          }
        };
      });
    } catch (e) {
      throw storageError(e);
    }
  }
  async replace(
    records: MemoryRecord[],
    expected: Partial<Record<GameId, number>>,
    backup = true,
  ): Promise<MemoryRecord[]> {
    // 导入前验证全部，失败时尚未启动写事务。
    const checked = records.map(validateRecord);
    if (
      !checked.length ||
      new Set(checked.map((r) => r.gameId)).size !== checked.length
    )
      fail();
    try {
      const db = await this.open();
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(["games", "backups"], "readwrite"),
          store = tx.objectStore("games");
        let error: unknown,
          result: MemoryRecord[] = [];
        tx.onabort = () => reject(storageError(error ?? tx.error));
        tx.oncomplete = () => resolve(result);
        const request = store.getAll();
        request.onsuccess = () => {
          try {
            const existing: MemoryRecord[] = request.result.map(validateRecord);
            result = checked.map((record) => {
              const previous = existing.find((r) => r.gameId === record.gameId);
              if ((previous?.revision ?? 0) !== expected[record.gameId])
                throw new MemoryError(
                  "conflict",
                  "另一页已有更新。本页进度已保留，请导出本页、读取另一页或明确覆盖。",
                );
              const revision = (previous?.revision ?? 0) + 1;
              if (!Number.isSafeInteger(revision))
                fail("记忆版本计数已超限，请导出后联系维护者。");
              if (backup && previous)
                tx.objectStore("backups").add({
                  id: crypto.randomUUID(),
                  original: previous,
                  backedUpAt: new Date().toISOString(),
                } satisfies Backup);
              const next = {
                ...record,
                revision,
                savedAt: new Date().toISOString(),
              };
              store.put(next);
              return next;
            });
          } catch (e) {
            error = e;
            tx.abort();
          }
        };
      });
    } catch (e) {
      throw storageError(e);
    }
  }
  async close() {
    const db = await this.connection;
    db?.close();
    this.connection = undefined;
  }
}
