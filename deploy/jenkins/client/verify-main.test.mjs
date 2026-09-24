import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';

test('发布接受 detached main，拒绝其他提交和缺失 main ref', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'wao-client-main-'));
  const script = fileURLToPath(new URL('./verify-main.sh', import.meta.url));
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const verify = () => spawnSync('bash', [script], { cwd, env: { ...process.env, GIT_BRANCH: '' } }).status;
  try {
    git('init');
    git('config', 'user.name', 'Release Test');
    git('config', 'user.email', 'release-test@example.invalid');
    git('commit', '--allow-empty', '-m', 'main');
    const main = git('rev-parse', 'HEAD');
    assert.notEqual(verify(), 0);
    git('update-ref', 'refs/remotes/origin/main', main);
    git('checkout', '--detach', main);
    assert.equal(verify(), 0);
    git('commit', '--allow-empty', '-m', 'other');
    assert.notEqual(verify(), 0);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});
