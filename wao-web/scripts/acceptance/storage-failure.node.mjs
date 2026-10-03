import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

test('故障只拒绝本页小记草稿写入，恢复后不损坏已有内容或其他存储', () => {
  class Storage {
    values = new Map()
    setItem(key, value) { this.values.set(key, value) }
    getItem(key) { return this.values.get(key) }
  }
  const persistent = new Storage(), session = new Storage()
  persistent.setItem('wao-note-edit-v1:member:doc:window-v2:tab', '原有草稿')
  let ready, button
  const context = { Storage, localStorage: persistent, DOMException,
    document: { addEventListener(name, action) { assert.equal(name, 'DOMContentLoaded'); ready = action },
      createElement() { return { style: {} } }, body: { append(value) { button = value } } } }
  vm.runInNewContext(readFileSync(new URL('./storage-failure.js', import.meta.url), 'utf8'), context)
  for (const key of ['wao-note-edit-v1:member:doc:window-v2:tab', 'wao-quick-note-draft-v1:member:window-v2:tab']) {
    assert.throws(() => persistent.setItem(key, '新输入'), { name: 'QuotaExceededError' })
  }
  assert.equal(persistent.getItem('wao-note-edit-v1:member:doc:window-v2:tab'), '原有草稿')
  persistent.setItem('other-app', '不受影响')
  session.setItem('wao-note-edit-v1:member:doc', '会话未被禁用')
  assert.equal(persistent.getItem('other-app'), '不受影响')
  assert.equal(session.getItem('wao-note-edit-v1:member:doc'), '会话未被禁用')
  ready(); button.onclick()
  persistent.setItem('wao-note-edit-v1:member:doc:window-v2:tab', '新输入')
  assert.equal(persistent.getItem('wao-note-edit-v1:member:doc:window-v2:tab'), '新输入')
  assert.equal(button.disabled, true)
})
