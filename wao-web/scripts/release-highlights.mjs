/** 将说明写入已有的不缓存版本清单，避免旧页面拿自身说明冒充新版本。 */
export function releaseHighlightsPlugin(edition, history) {
  if (history) {
    const ids = history.map(item => item.id)
    if (new Set(ids).size !== ids.length) throw new Error('更新档案存在重复批次')
    if (!history.some(item => JSON.stringify(item) === JSON.stringify(edition)))
      throw new Error('请将本次更新完整加入 releaseHistory.json，并保留历史记录')
  }
  return {
    name: 'wao-release-highlights',
    apply: 'build',
    enforce: 'post',
    generateBundle: {
      order: 'post',
      handler(_options, bundle) {
        const asset = bundle['pluginWebUpdateNotice/web_version_by_plugin.json']
        if (!asset || asset.type !== 'asset') throw new Error('缺少网页版本清单，无法绑定更新说明')
        const source =
          typeof asset.source === 'string' ? asset.source : new TextDecoder().decode(asset.source)
        const manifest = JSON.parse(source)
        if (typeof manifest.version !== 'string' || !manifest.version)
          throw new Error('网页版本标记无效')
        asset.source = JSON.stringify({ ...manifest, release: edition })
      },
    },
  }
}
