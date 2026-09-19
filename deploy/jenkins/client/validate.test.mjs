import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateVersion, requireNewVersion } from './validate.mjs';

test('拒绝重打包同版本和降级，正确比较数值版本', () => {
  assert.doesNotThrow(() => requireNewVersion('1.1.10', '1.1.9'));
  for (const version of ['1.1.9', '1.1.8', '0.9.99']) {
    assert.throws(() => requireNewVersion(version, '1.1.9'), /禁止重打包/);
  }
  for (const published of [undefined, '01.2.3', 'broken']) {
    assert.throws(() => requireNewVersion('1.1.9', published), /线上发布版本无效/);
  }
});

test('发布版本必须与全部锁文件和原生配置一致', () => {
  const root = mkdtempSync(join(tmpdir(), 'wao-client-version-'));
  const write = (path, value) => writeFileSync(join(root, path), value);
  try {
    mkdirSync(join(root, 'src-tauri'));
    write('package.json', '{"version":"1.1.9"}');
    write('package-lock.json', '{"version":"1.1.9","packages":{"":{"version":"1.1.9"}}}');
    write('src-tauri/tauri.conf.json', '{"version":"1.1.9"}');
    write('src-tauri/Cargo.toml', '[package]\nname = "wao-client"\nversion = "1.1.9"\n');
    write('src-tauri/Cargo.lock', '[[package]]\nname = "wao-client"\nversion = "1.1.9"\n');
    assert.equal(validateVersion(root), '1.1.9');
    write('src-tauri/Cargo.lock', '[[package]]\nname = "wao-client"\nversion = "1.1.8"\n');
    assert.throws(() => validateVersion(root), /不一致/);
    write('package.json', '{"version":"1.1.9-rc1"}');
    assert.throws(() => validateVersion(root), /稳定语义版本/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
