/** A03/A26：自有数据集两看板固定引用、显式升级及文本渲染。原5/981仅作只读模板。 */
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, expect } from '@playwright/test'
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'

const ac = new FormAcceptance(process.env.NOCODE_VERIFY_API)
const origin = process.env.NOCODE_VERIFY_URL || 'http://127.0.0.1:5173'
const output = resolve('.work/report-demo/p8-fixed-datasets', ac.prefix)
await mkdir(output, { recursive: true })
ac.output = output
const checks = [],
  errors = [],
  cleanupErrors = [],
  ownedBoards = []
let browser, page, dataset, sourceBoard, sourceDataset, originalManifest, failure
const get = (kind, id) => ac.api(`/nocode/report/${kind}/get?id=${id}`)
const publish = (kind, value) =>
  ac.api(`/nocode/report/${kind}/publish`, {
    id: value.id,
    expectedRevision: value.revision,
    requestId: randomUUID(),
    ...(kind === 'dataset' ? { reason: ac.prefix + '固定版本验收' } : {})
  })
const query = release =>
  ac.api('/nocode/report/dashboard/query', {
    id: release.id,
    chartId: 'table',
    preview: false,
    versionNo: release.versionNo,
    checksum: release.checksum
  })
async function openBoard(id, text, file) {
  await page.goto(`${origin}/nocode/report-center/dashboard-view?id=${id}`)
  await expect(page.locator('.dashboard-tile').getByText(text, { exact: true }).first()).toBeVisible()
  await expect(page.locator('.dashboard-tile .ant-spin-spinning')).toHaveCount(0)
  await page.screenshot({ path: resolve(output, file), fullPage: true, animations: 'disabled' })
}
try {
  await ac.login()
  const info = await ac.api('/system/auth/get-permission-info')
  sourceBoard = await get('dashboard', '5')
  sourceDataset = await get('dataset', '981')
  originalManifest = await readFile('.work/report-demo/current.json', 'utf8')
  const policy = await ac.api('/nocode/report/dataset/data-policy?id=981')
  const permissions = policy.members.find(
    m => m.principalKind === 'USER' && String(m.principalId) === String(info.user.id)
  )?.objects
  assert.ok(permissions?.length, '只能复制当前用户已登记的精确数据范围')
  dataset = await ac.api('/nocode/report/dataset/copy', {
    id: '981',
    expectedRevision: sourceDataset.revision,
    name: ac.prefix + '固定字段别名',
    reason: ac.prefix
  })
  const ceilings = await ac.api('/nocode/report/dataset/ceilings?id=' + dataset.id)
  for (const permission of permissions)
    await ac.api('/nocode/report/dataset/ceiling', {
      datasetId: dataset.id,
      objectId: permission.objectId,
      expectedRevision: ceilings.find(c => c.objectId === permission.objectId)?.revision || 0,
      permission,
      reason: ac.prefix
    })
  const copiedPolicy = await ac.api('/nocode/report/dataset/data-policy?id=' + dataset.id)
  await ac.api('/nocode/report/dataset/data-policy', {
    datasetId: dataset.id,
    expectedRevision: copiedPolicy.revision,
    members: [{ principalKind: 'USER', principalId: String(info.user.id), objects: permissions }],
    reason: ac.prefix
  })
  const template = sourceBoard.draft.charts.find(chart => chart.display === 'TABLE' && chart.dimensions.length)
  assert.ok(template, '模板需有实际分组汇总表')
  const fieldMap = Object.fromEntries(
    sourceDataset.draft.source.fields.map(field => [
      field.id,
      dataset.draft.source.fields.find(
        copy => copy.sourceNodeId === field.sourceNodeId && copy.sourceFieldId === field.sourceFieldId
      )?.id ||
        dataset.draft.source.fields.find(copy => copy.name === field.name && copy.sourceFieldId === field.sourceFieldId)
          ?.id
    ])
  )
  const metricMap = Object.fromEntries(
    sourceDataset.draft.analysis.metrics.map(metric => [
      metric.id,
      dataset.draft.analysis.metrics.find(copy => copy.name === metric.name && copy.operation === metric.operation)?.id
    ])
  )
  const dimensionId = fieldMap[template.dimensions[0].fieldId]
  assert.ok(dimensionId)
  const aliasV1 = ac.prefix + '公司旧别名'
  const aliasV2 = ac.prefix + '公司新别名'
  const content = structuredClone(dataset.draft)
  content.source.fields.find(field => field.id === dimensionId).name = aliasV1
  dataset = await ac.api('/nocode/report/dataset/save', {
    id: dataset.id,
    expectedRevision: dataset.revision,
    ...content
  })
  const v1 = await publish('dataset', dataset)
  const chart = {
    ...structuredClone(template),
    id: 'table',
    title: '固定版本汇总',
    dataset: { id: dataset.id, versionNo: v1.versionNo, checksum: v1.checksum },
    dimensions: template.dimensions.map(d => ({ ...d, fieldId: fieldMap[d.fieldId] })),
    metricIds: template.metricIds.map(id => metricMap[id]),
    links: [],
    drillDimensions: []
  }
  for (const key of ['columnDimensions'])
    if (chart[key]) chart[key] = chart[key].map(d => ({ ...d, fieldId: fieldMap[d.fieldId] }))
  for (const suffix of ['甲', '乙']) {
    const board = await ac.api('/nocode/report/dashboard/save', {
      id: null,
      expectedRevision: null,
      content: {
        schemaVersion: 1,
        name: ac.prefix + '固定看板' + suffix,
        description: '',
        charts: [chart],
        filters: []
      }
    })
    ownedBoards.push({ id: board.id, name: board.draft.name })
    await publish('dashboard', board)
  }
  let releases = await Promise.all(ownedBoards.map(b => ac.api('/nocode/report/dashboard/published?id=' + b.id)))
  const originals = await Promise.all(releases.map(query))
  originals.forEach(result => assert.equal(result.dimensionNames[0], aliasV1))
  dataset = await get('dataset', dataset.id)
  const changed = structuredClone(dataset.draft)
  changed.source.fields.find(field => field.id === dimensionId).name = aliasV2
  dataset = await ac.api('/nocode/report/dataset/save', {
    id: dataset.id,
    expectedRevision: dataset.revision,
    ...changed
  })
  const v2 = await publish('dataset', dataset)
  for (let i = 0; i < 2; i++) assert.deepEqual(await query(releases[i]), originals[i], '数据集新版本不得更改旧看板口径')
  browser = await chromium.launch({ headless: false, channel: 'chrome' })
  page = await browser.newPage({ viewport: { width: 1512, height: 982 }, reducedMotion: 'reduce' })
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(
    ({ token, info }) => {
      localStorage.setItem('token', token)
      for (const [key, value] of Object.entries({
        userInfo: info.user,
        permissions: info.permissions,
        roles: info.roles,
        menus: info.menus
      }))
        localStorage.setItem(key, JSON.stringify(value))
      sessionStorage.setItem('lastActivityAt', String(Date.now()))
    },
    { token: ac.tokens.admin, info }
  )
  await openBoard(ownedBoards[0].id, aliasV1, '01-old-a.png')
  await openBoard(ownedBoards[1].id, aliasV1, '02-old-b.png')
  checks.push('同一数据集两张看板固定V1；别名V2发布后两者实际SQL结果逐项不变，Chrome均保留旧列标题')
  let first = await get('dashboard', ownedBoards[0].id)
  first.draft.charts[0].dataset = { id: dataset.id, versionNo: v2.versionNo, checksum: v2.checksum }
  first = await ac.api('/nocode/report/dashboard/save', {
    id: first.id,
    expectedRevision: first.revision,
    content: first.draft
  })
  const upgraded = await publish('dashboard', first)
  const updated = await query(upgraded)
  assert.equal(updated.dimensionNames[0], aliasV2)
  assert.deepEqual(updated.groups, originals[0].groups)
  assert.deepEqual(updated.totals, originals[0].totals)
  assert.deepEqual(await query(releases[1]), originals[1])
  await openBoard(first.id, aliasV2, '03-explicit-upgrade.png')
  await openBoard(ownedBoards[1].id, aliasV1, '04-other-still-old.png')
  checks.push('只显式升级甲看板到数据集V2后列标题变化；乙仍固定V1，两个入口分组原键/金额与业务记录均不变')
  const payload = '<img src=x onerror="window.__reportPayloadExecuted=1">'
  first = await get('dashboard', first.id)
  first.draft.charts[0].title = payload
  first = await ac.api('/nocode/report/dashboard/save', {
    id: first.id,
    expectedRevision: first.revision,
    content: first.draft
  })
  await publish('dashboard', first)
  await openBoard(first.id, aliasV2, '05-safe-title.png')
  await expect(page.getByText(payload, { exact: true })).toBeVisible()
  assert.equal(await page.evaluate(() => window.__reportPayloadExecuted), undefined)
  await expect(page.locator('.dashboard-tile img')).toHaveCount(0)
  checks.push('带img/onerror的看板标题按文本渲染，真实Chrome未创建图片元素或执行payload')
  assert.deepEqual(errors, [])
} catch (error) {
  failure = error
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
} finally {
  await browser?.close()
  for (const board of ownedBoards.reverse())
    try {
      const current = await get('dashboard', board.id)
      assert.equal(current.draft.name, board.name)
      const preview = await ac.api('/nocode/report/dashboard/delete-preview?id=' + board.id)
      assert.equal(preview.canDelete, true)
      await ac.api('/nocode/report/dashboard/delete', {
        id: board.id,
        expectedRevision: current.revision,
        reason: ac.prefix + '精确清理'
      })
    } catch (error) {
      cleanupErrors.push(error.message)
    }
  if (dataset)
    try {
      const current = await get('dataset', dataset.id)
      assert.equal(current.draft.name, ac.prefix + '固定字段别名')
      const preview = await ac.api('/nocode/report/dataset/delete-preview?id=' + dataset.id)
      assert.equal(preview.canDelete, true)
      await ac.api('/nocode/report/dataset/delete', {
        id: dataset.id,
        expectedRevision: current.revision,
        reason: ac.prefix + '精确清理'
      })
    } catch (error) {
      cleanupErrors.push(error.message)
    }
  if (sourceBoard)
    try {
      assert.deepEqual(await get('dashboard', '5'), sourceBoard)
      assert.deepEqual(await get('dataset', '981'), sourceDataset)
      assert.equal(await readFile('.work/report-demo/current.json', 'utf8'), originalManifest)
    } catch (error) {
      cleanupErrors.push(error.message)
    }
  const result = {
    status: !failure && !cleanupErrors.length ? 'PASSED' : 'FAILED',
    prefix: ac.prefix,
    checks,
    errors,
    cleanupErrors,
    failure: failure?.message,
    datasetId: dataset?.id,
    boards: ownedBoards
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(
    JSON.stringify({ output, status: result.status, checks: checks.length, failure: result.failure, cleanupErrors })
  )
}
if (failure || cleanupErrors.length) process.exitCode = 1
