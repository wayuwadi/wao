import { spawn } from 'node:child_process'
import { homedir } from 'node:os'
import { delimiter, resolve } from 'node:path'

// A fresh Windows shell may not have the Rust path yet. Keep this scoped to the child.
const env = { ...process.env, PATH: `${resolve(homedir(), '.cargo/bin')}${delimiter}${process.env.PATH}` }
const args = process.argv.slice(2)
if (args[0] === 'build' && !env.TAURI_SIGNING_PRIVATE_KEY) {
  args.push('--config', JSON.stringify({ bundle: { createUpdaterArtifacts: false } }))
}
const child = spawn(process.execPath, ['node_modules/@tauri-apps/cli/tauri.js', ...args], { cwd: new URL('..', import.meta.url), env, stdio: 'inherit' })
child.on('exit', code => process.exit(code ?? 1))
child.on('error', error => { console.error(error.message); process.exit(1) })
