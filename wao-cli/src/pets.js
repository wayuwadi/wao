import { readFile, stat, mkdir, writeFile } from 'node:fs/promises'
import { basename, extname, resolve } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { createInterface } from 'node:readline/promises'
import { MAX_PET_BYTES, petId, petPageUrl, petWorkspaceUrl } from './pets-client.js'

export const PET_HELP = `用法：
  wao pets list
  wao pets upload --file ZIP --source 素材来源 [--license ALL-RIGHTS-RESERVED] [--receipt-file NEW_FILE]
  wao pets share ID [--yes] [--receipt-file NEW_FILE]
  wao pets unshare ID [--receipt-file NEW_FILE]
上传只保存到我的 Pet；公开分享需要单独确认素材权利。
授权方式：MIT、Apache-2.0、CC-BY-4.0、CC0-1.0、ALL-RIGHTS-RESERVED。
ZIP 最大 10 MiB；服务端验证 Pet 包格式。每个操作独立检查当前令牌授权。
Pet 工作区地址路径必须是 /api/mcp/workspace；不支持路径前缀。
回执在写入前持久保存；出现未知结果先用 pets list 核对，不自动重试。
分享成功后匿名验证公开元数据和下载包 SHA-256；验证失败时仍记录已保存的公开状态。`

function parse(words, action) {
  const flags = new Map()
  const positional = []
  const allowed = action === 'upload' ? ['file', 'source', 'license', 'receipt-file'] : action === 'share' ? ['yes', 'receipt-file'] : action === 'unshare' ? ['receipt-file'] : []
  for (let i = 0; i < words.length; i++) {
    const word = words[i]
    if (!word.startsWith('--')) { positional.push(word); continue }
    const name = word.slice(2)
    if (!allowed.includes(name)) throw new Error('Pet 命令包含不支持的选项；运行 wao pets --help 查看用法')
    if (flags.has(name)) throw new Error('Pet 命令包含重复选项')
    if (name === 'yes') { flags.set(name, true); continue }
    const value = words[++i]
    if (!value || value.startsWith('--')) throw new Error('--' + name + ' 缺少值')
    flags.set(name, value)
  }
  if (positional.length !== (['share', 'unshare'].includes(action) ? 1 : 0)) throw new Error('Pet 位置参数无效；share/unshare 需要一个 ID，list/upload 不接受位置参数')
  if (positional[0]) petId(positional[0])
  if (flags.get('receipt-file') === '-') throw new Error('--receipt-file 必须是持久文件路径')
  return { flags, id: positional[0] }
}
async function prepareArchive(flags) {
  if (!flags.has('file') || !flags.has('source')) throw new Error('上传需要 --file ZIP 和 --source 素材来源')
  const source = flags.get('source').trim()
  if (!source || source.length > 500) throw new Error('--source 必须为 1–500 字素材来源')
  const license = flags.get('license') || 'ALL-RIGHTS-RESERVED'
  if (!['MIT', 'Apache-2.0', 'CC-BY-4.0', 'CC0-1.0', 'ALL-RIGHTS-RESERVED'].includes(license)) throw new Error('--license 不是受支持的授权方式')
  const file = resolve(flags.get('file'))
  if (extname(file).toLowerCase() !== '.zip') throw new Error('--file 必须是 .zip 文件')
  const info = await stat(file).catch(() => null)
  if (!info?.isFile()) throw new Error('找不到 Pet ZIP 文件')
  if (!info.size || info.size > MAX_PET_BYTES) throw new Error('Pet ZIP 大小必须大于 0 且不超过 10 MiB')
  const archive = await readFile(file)
  if (!archive.length || archive.length > MAX_PET_BYTES) throw new Error('Pet ZIP 大小必须大于 0 且不超过 10 MiB')
  if (archive.length < 4 || archive.readUInt32LE(0) !== 0x04034b50) throw new Error('文件不是有效的 Pet ZIP（缺少 ZIP 文件头）')
  return { fileName: basename(file), archive, source, license, archiveSha256: createHash('sha256').update(archive).digest('hex') }
}
function receiptText(receipt) { return JSON.stringify(receipt, null, 2) + '\n' }
async function prepareReceipt(action, identity, data, requested, directory, stderr) {
  const file = resolve(requested || resolve(directory, 'pets-' + action + '-' + identity + '.json'))
  if (!requested) await mkdir(directory, { recursive: true })
  const receipt = { command: 'pets ' + action, ...data, state: 'prepared', created_at: new Date().toISOString() }
  try { await writeFile(file, receiptText(receipt), { encoding: 'utf8', flag: 'wx', mode: 0o600 }) }
  catch (error) {
    if (error.code === 'EEXIST') throw new Error('回执已存在；先用 wao pets list 核对原结果，不会重新上传或修改：' + file)
    throw new Error('无法保存 Pet 操作回执；未发出写请求')
  }
  stderr.write('Pet 操作回执：' + file + '\n')
  return { file, receipt }
}
async function saveReceipt(pending, data) {
  await writeFile(pending.file, receiptText({ ...pending.receipt, finished_at: new Date().toISOString(), ...data }), { encoding: 'utf8', mode: 0o600 })
}
async function writeWithReceipt(pending, write) {
  let card
  try { card = await write() }
  catch (error) {
    await saveReceipt(pending, { state: 'unknown' }).catch(() => {})
    throw new Error(error.message + '；结果待核对，未自动重试。回执：' + pending.file)
  }
  try { await saveReceipt(pending, { state: 'succeeded', persistedListing: card.listed, result: card }) }
  catch { throw new Error('Pet 写入已成功，但回执保存失败；请先用 wao pets list 核对，不要直接重试。回执：' + pending.file) }
  return card
}
export async function confirmPetShare(id, publicUrl, stdin, stdout) {
  if (!stdin.isTTY) throw new Error('公开分享需要交互确认；确认拥有素材分享权利后，自动化可显式使用 --yes')
  stdout.write('即将公开分享 Pet：' + id + '\n公开地址：' + publicUrl + '\n请确认你拥有分享此包的权利，并接受公开下载。输入 yes 继续：\n')
  const prompt = createInterface({ input: stdin, output: stdout })
  try {
    if ((await prompt.question('> ')).trim().toLowerCase() !== 'yes') throw new Error('已取消分享')
  } finally { prompt.close() }
}

export async function runPets(rest, { url, stdout, stderr, stdin, tokenProvider, clientFactory, receiptDirectory, confirmShare = confirmPetShare, formatOutput, output }) {
  if (rest.length === 1 || (rest.length === 2 && ['--help', '-h'].includes(rest[1])) || (rest.length === 3 && ['list', 'upload', 'share', 'unshare'].includes(rest[1]) && ['--help', '-h'].includes(rest[2]))) {
    stdout.write(PET_HELP + '\n'); return
  }
  const action = rest[1]
  if (!['list', 'upload', 'share', 'unshare'].includes(action)) throw new Error('未知 Pet 操作；运行 wao pets --help 查看用法')
  const { flags, id } = parse(rest.slice(2), action)
  petWorkspaceUrl(url)
  const archive = action === 'upload' ? await prepareArchive(flags) : null
  const client = clientFactory({ url, token: await tokenProvider() })
  await client.checkPetPermission(action === 'list' ? 'list_my_pets' : action === 'upload' ? 'upload_pet' : 'share_pet')
  if (action === 'list') { stdout.write(formatOutput(await client.listPets(), output) + '\n'); return }
  if (action === 'share' && !flags.has('yes')) await confirmShare(id, petPageUrl(url, id), stdin, stdout)
  const identity = archive ? createHash('sha256').update(JSON.stringify([url, archive.archiveSha256, archive.source, archive.license])).digest('hex') : randomUUID()
  const pending = await prepareReceipt(action, identity, archive ? { endpoint: url, archiveSha256: archive.archiveSha256, source: archive.source, license: archive.license } : { endpoint: url, petId: id, requestedListing: action === 'share' }, flags.get('receipt-file'), receiptDirectory, stderr)
  const card = await writeWithReceipt(pending, () => archive ? client.uploadPet(archive) : client.setPetVisibility(id, action === 'share'))
  if (action === 'unshare' && card.listed) throw new Error('Pet 保存结果仍为公开状态；请用 wao pets list 核对。回执：' + pending.file)
  if (action !== 'share') { stdout.write(formatOutput({ ...card, receiptFile: pending.file }, output) + '\n'); return }
  const publicUrl = petPageUrl(url, card.id)
  const verification = await client.verifyPet(card)
  try { await saveReceipt(pending, { state: 'succeeded', persistedListing: card.listed, result: card, publicUrl, verification }) }
  catch { throw new Error('Pet 公开状态已保存，但核验回执保存失败；请检查 ' + publicUrl + '；回执：' + pending.file) }
  if (!card.listed || !verification.ok) throw new Error('Pet 公开状态已保存（listed=' + card.listed + '），但匿名核验未通过：' + (verification.message || '服务端未保存公开状态') + '；请核对 ' + publicUrl + '；回执：' + pending.file)
  stdout.write(formatOutput({ ...card, publicUrl, verification, receiptFile: pending.file }, output) + '\n')
}
