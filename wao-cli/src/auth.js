import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const SERVICE = 'cn.wayuwa.wao.cli'
const ACCOUNT = 'workspace-token'
const WORKSPACE_TOKEN = /(?<![A-Za-z0-9_-])wao_workspace_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/g

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

export async function getToken() {
  return process.env.WAO_TOKEN?.trim() || await tokenFromKeychain()
}

export async function storeToken(token) {
  if (process.platform !== 'darwin') throw new Error('当前系统请使用 WAO_TOKEN 环境变量')
  token = normalizeWorkspaceToken(token)
  // -w 位于最后且不带参数，security 从 stdin 读取两次确认；令牌不会出现在进程参数中。
  await new Promise((resolve, reject) => {
    const child = execFile('security', ['add-generic-password', '-a', ACCOUNT, '-s', SERVICE, '-U', '-w'], error => {
      if (error) reject(new Error('无法写入 macOS 钥匙串'))
      else resolve()
    })
    child.stdin.end(`${token}\n${token}\n`)
  })
}

export async function removeToken() {
  if (process.platform !== 'darwin') throw new Error('当前系统请自行移除 WAO_TOKEN 环境变量')
  try { await exec('security', ['delete-generic-password', '-a', ACCOUNT, '-s', SERVICE]) }
  catch (error) {
    if (error.code !== 44) throw new Error('无法删除 macOS 钥匙串中的 WAO 令牌')
  }
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
