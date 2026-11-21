import { execFile } from 'node:child_process'
import { homedir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { promisify } from 'node:util'

const runExecutable = promisify(execFile)

export async function codeupStatus(id, { executable = process.env.WAO_CODEUP_CLI || join(homedir(), '.local', 'bin', 'codeup-cli'),
  execute = runExecutable } = {}) {
  if (!Number.isSafeInteger(id) || id < 1) throw new Error('合并请求编号须为正整数。')
  if (!isAbsolute(executable)) throw new Error('WAO_CODEUP_CLI 必须是已配置的本机 Codeup CLI 的绝对路径。')

  let output
  try {
    output = await execute(executable, ['status', String(id)], {
      cwd: dirname(executable), timeout: 55_000, maxBuffer: 64 * 1024, windowsHide: true,
    })
  } catch (error) {
    // The underlying CLI owns authentication. Its raw diagnostics can include private data.
    if (error.code === 'ENOENT' || error.code === 'EACCES') {
      throw new Error('未找到可执行的本机 Codeup CLI，请检查 WAO_CODEUP_CLI。')
    }
    const diagnostic = String(error.stderr ?? '')
    if (diagnostic.includes('钥匙串缺少条目') || /\b(?:InvalidPersonalAccessToken|ExpiredPersonalAccessToken|Unauthorized)\b/i.test(diagnostic)) {
      throw new Error('AUTH_REQUIRED：请在本机 Codeup CLI 配置或更新授权，然后重试。')
    }
    if (diagnostic.includes('无法读取钥匙串条目')) throw new Error('无法访问本机 Codeup 凭据，请检查钥匙串。')
    if (error.killed || diagnostic.includes('操作超时')) throw new Error('Codeup 查询超时，请检查本机钥匙串或网络。')
    throw new Error('Codeup 查询失败，请检查本机 CLI、访问权限及网络连接。')
  }

  let response
  try { response = JSON.parse(output.stdout) } catch { throw new Error('本机 Codeup CLI 未返回有效的 JSON。') }
  if (String(response?.localId) !== String(id) || typeof response.status !== 'string' || !/^[a-z][a-z0-9_-]{0,39}$/i.test(response.status)) {
    throw new Error('Codeup 返回的合并请求编号或状态与预期不符。')
  }
  const result = { localId: id, status: response.status }
  if (typeof response.targetBranch === 'string' && /^[\p{L}\p{N}._/-]{1,255}$/u.test(response.targetBranch)) {
    result.targetBranch = response.targetBranch
  }
  if (typeof response.mergedRevision === 'string' && /^[a-f0-9]{40}(?:[a-f0-9]{24})?$/i.test(response.mergedRevision)) {
    result.mergedRevision = response.mergedRevision
  }
  return result
}
