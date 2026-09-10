import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, readFileSync, rmSync, statSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { prepareDevHost } from "./dev-host-files.mjs";

const directories = [];
afterEach(() => directories.splice(0).forEach((directory) => rmSync(directory, { recursive: true, force: true })));

describe("开发宿主进程隔离", () => {
  function fixture() {
    const directory = mkdtempSync(join(tmpdir(), "wao-dev-host-test-"));
    directories.push(directory);
    const source = join(directory, "wao-client.exe");
    writeFileSync(source, "first host build");
    return { source, runtime: join(directory, "runtime") };
  }

  it("使用独立进程名且保留下载制品字节", () => {
    const { source, runtime } = fixture();
    const executable = prepareDevHost(source, runtime);
    expect(basename(executable)).toBe("wao-client-dev.exe");
    expect(executable).not.toBe(source);
    expect(readFileSync(executable)).toEqual(readFileSync(source));
  });

  it("相同宿主不重复覆盖运行文件", () => {
    const { source, runtime } = fixture();
    const executable = prepareDevHost(source, runtime);
    const fixedTime = new Date("2020-01-01T00:00:00Z");
    utimesSync(executable, fixedTime, fixedTime);
    prepareDevHost(source, runtime);
    expect(statSync(executable).mtimeMs).toBe(fixedTime.getTime());
  });

  it("下载新宿主后更新运行副本", () => {
    const { source, runtime } = fixture();
    const executable = prepareDevHost(source, runtime);
    writeFileSync(source, "updated host build");
    prepareDevHost(source, runtime);
    expect(readFileSync(executable, "utf8")).toBe("updated host build");
    expect(readFileSync(source, "utf8")).toBe("updated host build");
  });
});
