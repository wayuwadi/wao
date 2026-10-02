import { test } from 'node:test'
import assert from 'node:assert/strict'
import { releaseHighlightsPlugin } from './release-highlights.mjs'
test('保留检测器的版本和静默标记，将对应说明绑定到同一个制品', () => {
  const bundle = {
    'pluginWebUpdateNotice/web_version_by_plugin.json': {
      type: 'asset',
      source: '{"version":"abcdef0","silence":true}',
    },
  }
  releaseHighlightsPlugin({ id: 'edition' }).generateBundle.handler({}, bundle)
  assert.deepEqual(JSON.parse(bundle['pluginWebUpdateNotice/web_version_by_plugin.json'].source), {
    version: 'abcdef0',
    silence: true,
    release: { id: 'edition' },
  })
})
test('缺失版本清单时构建失败，不能发布不匹配说明', () => {
  assert.throws(
    () => releaseHighlightsPlugin({}).generateBundle.handler({}, {}),
    /缺少网页版本清单/,
  )
})
