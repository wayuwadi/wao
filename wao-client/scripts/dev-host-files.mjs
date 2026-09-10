import { copyFileSync, existsSync, mkdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

// NSIS closes processes by executable name, even when app identifiers differ.
// Keep the downloaded artifact intact and launch a distinctly named copy.
export function prepareDevHost(source, runtimeDirectory) {
  const executable = resolve(runtimeDirectory, "wao-client-dev.exe");
  if (resolve(source) === executable) return executable;
  mkdirSync(runtimeDirectory, { recursive: true });
  if (
    !existsSync(executable) ||
    !readFileSync(source).equals(readFileSync(executable))
  ) {
    copyFileSync(source, executable);
  }
  return executable;
}
