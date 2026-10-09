import assert from 'node:assert/strict'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

// 仅处理本工具 manifest 精确登记、编码一致且从未发布的 UI 草稿；不清理真实发布验收夹具。
const root = resolve('.work/auto-number-ui')
const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API, resolve(root, 'cleanup'))
const results = []
try {
  await ac.login()
  for (const entry of await readdir(root, { withFileTypes: true })) {
    if (!entry.isDirectory() || !/^fa[a-z0-9]+$/.test(entry.name)) continue
    const manifest = JSON.parse(await readFile(resolve(root, entry.name, 'http-result.json'), 'utf8'))
    assert.equal(manifest.prefix, entry.name)
    for (const owned of manifest.owned.objects) {
      assert.equal(owned.code, `${manifest.prefix}_number_ui`)
      const result = await ac.request(`/nocode/design/get?id=${owned.id}`)
      if (result.code !== 0) {
        results.push({ id: owned.id, code: owned.code, skipped: result.msg })
        continue
      }
      const design = result.data
      assert.equal(String(design.draft.id), String(owned.id))
      assert.equal(design.draft.objectCode, owned.code)
      assert.ok(!design.publishedVersion, '已发布对象不能由纯 UI 清理脚本删除')
      if (design.draft.status === 'DELETED') {
        results.push({ id: owned.id, alreadyDeleted: true })
        continue
      }
      await ac.api('/nocode/design/delete', {
        id: owned.id,
        expectedLockVersion: design.draft.lockVersion,
        reason: '清理已完成自动编号纯 UI 验收草稿'
      })
      results.push({ id: owned.id, code: owned.code, deleted: true })
    }
  }
} finally {
  ac.tokens = {}
  ac.passwords = {}
  await writeFile(
    resolve(root, 'cleanup-result.json'),
    JSON.stringify({ time: new Date().toISOString(), results }, null, 2)
  )
  console.log(
    JSON.stringify({ output: resolve(root, 'cleanup-result.json'), deleted: results.filter(r => r.deleted).length })
  )
}
