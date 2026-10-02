/** 将说明写入已有的不缓存版本清单，避免旧页面拿自身说明冒充新版本。 */
export function releaseHighlightsPlugin(edition) {
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
