import axios from 'axios'

/** 读取部署时生成的版本标记，绕过缓存，不把登录页或错误页当作版本。 */
export async function fetchWebVersion(): Promise<string> {
  const { data } = await axios.get('/pluginWebUpdateNotice/web_version_by_plugin.json', {
    params: { t: Date.now() }, timeout: 10000, headers: { 'Cache-Control': 'no-cache' },
  })
  if (typeof data?.version !== 'string' || !data.version.trim()) throw new Error('版本信息无效，请稍后重试')
  return data.version.trim()
}
