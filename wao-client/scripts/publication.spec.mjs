import { it, expect } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

it.skipIf(process.platform !== 'linux')('publishing rejects changed versioned bytes and downgrades without changing the live manifest', () => {
  const directory = mkdtempSync(join(tmpdir(), 'wao-publish-test-'));
  const raw = join(directory, 'raw'), destination = join(directory, 'published');
  mkdirSync(raw);
  writeFileSync(join(raw, 'WAO-setup.exe'), 'windows fixture');
  writeFileSync(join(raw, 'WAO-setup.exe.sig'), 'windows fixture signature for tests');
  writeFileSync(join(raw, 'WAO.dmg'), 'dmg fixture');
  writeFileSync(join(raw, 'WAO.app.tar.gz'), 'universal fixture');
  writeFileSync(join(raw, 'WAO.app.tar.gz.sig'), 'macos fixture signature for tests');
  let attempt = 0;
  const incomingPaths = [];
  function publish(version) {
    const incoming = `/tmp/wao-client-${process.pid}-${++attempt}`;
    incomingPaths.push(incoming);
    expect(spawnSync(process.execPath, [resolve('scripts/prepare-release.mjs'), raw, incoming, version]).status).toBe(0);
    return spawnSync('bash', [resolve('scripts/publish.sh'), incoming, version], {
      env: { ...process.env, WAO_CLIENT_PUBLISH_ROOT: destination }, encoding: 'utf8',
    });
  }
  try {
    expect(publish('1.0.1').status).toBe(0);
    expect(publish('1.0.1').status).toBe(0);
    const previous = readFileSync(join(destination, 'latest.json'), 'utf8');
    writeFileSync(join(raw, 'WAO.dmg'), 'different DMG bytes');
    expect(publish('1.0.1').status).not.toBe(0);
    expect(readFileSync(join(destination, 'latest.json'), 'utf8')).toBe(previous);
    expect(readFileSync(join(destination, 'WAO-macos-universal.dmg'), 'utf8')).toBe('dmg fixture');
    expect(publish('1.0.0').status).not.toBe(0);
    expect(readFileSync(join(destination, 'latest.json'), 'utf8')).toBe(previous);
  } finally {
    rmSync(directory, { recursive: true, force: true });
    incomingPaths.forEach(path => rmSync(path, { recursive: true, force: true }));
  }
});
