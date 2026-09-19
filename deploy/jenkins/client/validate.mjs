import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export function validateVersion(root) {
  const read = (name) => readFileSync(resolve(root, name), 'utf8');
  const pkg = JSON.parse(read('package.json'));
  const version = pkg.version;
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version ?? '')) {
    throw new Error('客户端必须使用稳定语义版本号');
  }
  const lock = JSON.parse(read('package-lock.json'));
  const cargoVersion = read('src-tauri/Cargo.toml').match(/^version = "([^"]+)"/m)?.[1];
  const cargoLockVersion = read('src-tauri/Cargo.lock').match(/\[\[package\]\]\s+name = "wao-client"\s+version = "([^"]+)"/)?.[1];
  const versions = [lock.version, lock.packages?.['']?.version,
    JSON.parse(read('src-tauri/tauri.conf.json')).version, cargoVersion, cargoLockVersion];
  if (versions.some((value) => value !== version)) throw new Error('客户端配置或锁文件版本不一致');
  return version;
}

export function requireNewVersion(version, published) {
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(published ?? '')) {
    throw new Error('线上发布版本无效，停止发布');
  }
  const next = version.split('.').map(Number);
  const old = published.split('.').map(Number);
  for (let index = 0; index < 3; index++) {
    if (next[index] > old[index]) return;
    if (next[index] < old[index]) break;
  }
  throw new Error('版本必须高于已发布版本；禁止重打包覆盖或降级');
}

if (process.argv[1] && resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const version = validateVersion('wao-client');
  if (process.env.PUBLISH_CLIENT === 'true') {
    const response = await fetch('https://wao.wayuwa.cn/downloads/desktop/client/latest.json', {
      cache: 'no-store', signal: AbortSignal.timeout(20000),
    });
    if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
      throw new Error('无法确认线上版本，停止发布');
    }
    requireNewVersion(version, (await response.json()).version);
  }
  console.log(version);
}
