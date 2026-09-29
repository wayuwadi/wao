import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const SERVICE = 'cn.wayuwa.wao.cli'
const ACCOUNT = 'workspace-token'
const WORKSPACE_TOKEN = /(?<![A-Za-z0-9_-])wao_workspace_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/g

const WINDOWS_READ_SCRIPT = [
  "$ErrorActionPreference = 'Stop'",
  "$path = Join-Path $env:APPDATA 'WAO\\workspace-token.dpapi'",
  "if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { [Console]::Out.Write('__WAO_TOKEN_MISSING__'); exit 0 }",
  "$cipher = [System.IO.File]::ReadAllBytes($path)",
  "$plain = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)",
  "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($plain))",
].join('\n')
const WINDOWS_STORE_SCRIPT = [
  "$ErrorActionPreference = 'Stop'",
  "$path = Join-Path $env:APPDATA 'WAO\\workspace-token.dpapi'",
  "$folder = Split-Path -Parent $path",
  "[System.IO.Directory]::CreateDirectory($folder) | Out-Null",
  "$token = [Console]::In.ReadToEnd().Trim()",
  "$plain = [Text.Encoding]::UTF8.GetBytes($token)",
  "$cipher = [Security.Cryptography.ProtectedData]::Protect($plain, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)",
  "[System.IO.File]::WriteAllBytes($path, $cipher)",
].join('\n')
const WINDOWS_DELETE_SCRIPT = [
  "$ErrorActionPreference = 'Stop'",
  "$path = Join-Path $env:APPDATA 'WAO\\workspace-token.dpapi'",
  "if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }",
].join('\n')

function runPowerShell(script, input = '') {
  const encoded = Buffer.from(script, 'utf16le').toString('base64')
  return new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], {
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''
    child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk })
    child.stderr.resume()
    child.once('error', () => reject(new Error('无法启动 Windows DPAPI 凭据存储')))
    child.once('close', code => {
      if (code !== 0) reject(new Error('Windows DPAPI 凭据存储操作失败'))
      else resolve(stdout)
    })
    child.stdin.end(input)
  })
}

/** 接受单独的令牌，也接受包含唯一工作区令牌的连接说明；绝不回显输入。 */
export function normalizeWorkspaceToken(input) {
  const matches = [...String(input).matchAll(WORKSPACE_TOKEN)]
  if (matches.length !== 1) {
    throw new Error('请只粘贴以 wao_workspace_ 开头的工作区令牌，或包含该令牌的连接说明；不要使用记忆 MCP 令牌')
  }
  return matches[0][0]
}

export async function tokenFromKeychain() {
  if (process.platform !== 'darwin') return null
  try {
    const { stdout } = await exec('security', ['find-generic-password', '-a', ACCOUNT, '-s', SERVICE, '-w'])
    return stdout.trim() || null
  } catch (error) {
    if (error.code === 44) return null
    throw new Error('无法读取 macOS 钥匙串；请检查钥匙串权限或改用 WAO_TOKEN')
  }
}

export async function tokenFromWindowsCredentialStore(windowsRunner = runPowerShell) {
  try {
    const value = (await windowsRunner(WINDOWS_READ_SCRIPT)).trim()
    return value === '__WAO_TOKEN_MISSING__' ? null : value || null
  } catch {
    throw new Error('无法读取当前 Windows 用户的 DPAPI 凭据；请检查本机凭据权限或改用 WAO_TOKEN')
  }
}

export async function getToken({ platform = process.platform, env = process.env,
  windowsRunner = runPowerShell } = {}) {
  if (env.WAO_TOKEN?.trim()) return env.WAO_TOKEN.trim()
  if (platform === 'darwin') return tokenFromKeychain()
  if (platform === 'win32') return tokenFromWindowsCredentialStore(windowsRunner)
  return null
}

export async function storeToken(token, { platform = process.platform,
  windowsRunner = runPowerShell } = {}) {
  token = normalizeWorkspaceToken(token)
  if (platform === 'darwin') {
    // -w 位于最后且不带参数，security 从 stdin 读取两次确认；令牌不会出现在进程参数中。
    await new Promise((resolve, reject) => {
      const child = execFile('security', ['add-generic-password', '-a', ACCOUNT, '-s', SERVICE, '-U', '-w'], error => {
        if (error) reject(new Error('无法写入 macOS 钥匙串'))
        else resolve()
      })
      child.stdin.end(token + '\n' + token + '\n')
    })
    return
  }
  if (platform === 'win32') {
    try { await windowsRunner(WINDOWS_STORE_SCRIPT, token) }
    catch { throw new Error('无法写入当前 Windows 用户的 DPAPI 凭据') }
    return
  }
  throw new Error('当前系统请使用 WAO_TOKEN 环境变量')
}

export async function removeToken({ platform = process.platform,
  windowsRunner = runPowerShell } = {}) {
  if (platform === 'darwin') {
    try { await exec('security', ['delete-generic-password', '-a', ACCOUNT, '-s', SERVICE]) }
    catch (error) {
      if (error.code !== 44) throw new Error('无法删除 macOS 钥匙串中的 WAO 令牌')
    }
    return
  }
  if (platform === 'win32') {
    try { await windowsRunner(WINDOWS_DELETE_SCRIPT) }
    catch { throw new Error('无法删除当前 Windows 用户的 DPAPI 凭据') }
    return
  }
  throw new Error('当前系统请自行移除 WAO_TOKEN 环境变量')
}

export async function readToken({ stdin = process.stdin, stdout = process.stdout, fromStdin = false } = {}) {
  if (fromStdin) {
    let value = ''
    for await (const chunk of stdin) value += chunk
    return value.trim()
  }
  if (!stdin.isTTY) throw new Error('交互登录需要终端；也可使用 auth login --token-stdin')
  stdout.write('粘贴以 wao_workspace_ 开头的令牌（输入时不显示）：')
  const chunks = []
  const previousRaw = stdin.isRaw
  stdin.setRawMode(true)
  stdin.resume()
  try {
    for await (const chunk of stdin) {
      const chars = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      for (const char of chars) {
        if (char === 3) throw new Error('已取消')
        if (char === 10 || char === 13) {
          const entered = Buffer.concat(chunks).toString()
          if (!entered.includes('请将以下 WAO MCP Server') || /wao_workspace_[A-Za-z0-9_-]{43}/.test(entered)) {
            stdout.write('\n')
            return entered.trim()
          }
          if (chunks.length > 16_384) throw new Error('连接说明过长；请只复制工作区令牌')
          chunks.push(Buffer.from('\n'))
          continue
        }
        if (char === 127 || char === 8) { chunks.pop(); continue }
        chunks.push(Buffer.from([char]))
        if (chunks.length > 16_384) throw new Error('输入过长；请只复制工作区令牌')
      }
    }
  } finally {
    stdin.setRawMode(previousRaw)
    stdin.pause()
  }
  throw new Error('没有读取到令牌')
}
