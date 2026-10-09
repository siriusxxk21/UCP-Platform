import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { BATCH, ConstructionExperience, OUTPUT } from './prepare.mjs'

// 仅用于交付前的准备阶段：验证现有实例与拒绝路径，不创建任务或成功新增业务数据。
const ac = new ConstructionExperience()
await ac.login()
const manifest = JSON.parse(await readFile(resolve(OUTPUT, 'manifest.json'), 'utf8'))
assert.equal(manifest.batch, BATCH)
assert.equal(manifest.complete, true)
ac.app = await ac.api(`/nocode/application/get?id=${manifest.applicationId}`)
for (const [key, object] of Object.entries(manifest.objects)) ac.catalog[key] = await ac.version(object.id)
const checks = []
async function check(name, run) {
  const detail = await run()
  checks.push({ name, passed: true, detail })
  console.log('PASS ' + name)
}
const tasks = context =>
  ac.api('/nocode/tasks/page-tasks', {
    applicationId: manifest.applicationId,
    ...context,
    query: { scope: 'VISIBLE', tab: 'ALL', pageNo: 1, pageSize: 100 }
  })
async function snapshot() {
  const application = await ac.api(`/nocode/application/get?id=${manifest.applicationId}`)
  const records = {}
  for (const [key, object] of Object.entries(ac.catalog)) records[key] = await ac.page(object)
  const authorization = await ac.api(`/nocode/application/authorization?id=${manifest.applicationId}`)
  return { application, records, authorization }
}

try {
  await check('五对象均为发布版本，应用当前发布版可访问，表单和视图齐全', async () => {
    const runtime = await ac.api(`/nocode/runtime/application?id=${manifest.applicationId}`)
    assert.equal(runtime.application.code, manifest.applicationCode)
    const resources = runtime.definition.resources
    assert.equal(resources.filter(item => item.kind === 'MENU').length, 7)
    assert.equal(resources.filter(item => item.kind === 'FORM').length, 5)
    assert.equal(resources.filter(item => item.kind === 'VIEW').length, 5)
    assert.equal(resources.filter(item => item.kind === 'TASK_ENTRY').length, 0)
    for (const [key, object] of Object.entries(ac.catalog)) {
      assert.equal(object.versionNo, manifest.objects[key].version)
      const model = await ac.api(
        `/nocode/runtime/model?applicationId=${manifest.applicationId}&objectId=${object.objectId}`
      )
      assert.ok(model.permissions.actions.includes('CREATE'))
      assert.ok(model.permissions.readFields.includes(object.ids.name))
    }
    return { applicationId: manifest.applicationId, version: runtime.versionNo, resources: resources.length }
  })
  await check('两个项目各一套业务记录，项目关联列表不会混入另一项目', async () => {
    for (const key of ['content', 'log', 'cost', 'inspection']) {
      const object = ac.catalog[key]
      for (const [tag, projectId] of Object.entries(manifest.seeded.project)) {
        const result = await ac.page(object, undefined, {
          viewId: `${key}_view`,
          context: { pageId: 'project_detail', nodeId: `project_${key}`, recordId: projectId }
        })
        assert.equal(result.total, 1, `${key}/${tag} 只有本项目示例`)
        assert.equal(String(result.list[0].values[object.ids.project_id]), projectId)
        assert.equal(result.list[0].id, manifest.seeded[key][tag])
      }
    }
    return { projects: 2, scopedLists: 8 }
  })
  await check('概览统计来自真实记录：2个项目、2项施工内容、20000元费用、2条验收', async () => {
    const actual = {}
    for (const [reportId, expected] of Object.entries({
      project_count: 2,
      content_count: 2,
      cost_total: 20000,
      inspection_count: 2
    })) {
      const result = await ac.api('/nocode/runtime/report', { applicationId: manifest.applicationId, reportId })
      actual[reportId] = Number(result.totals.value)
      assert.equal(actual[reportId], expected)
    }
    return actual
  })
  await check('应用任务与两个项目任务均为空，未替用户创建任务', async () => {
    assert.equal((await tasks({ pageId: 'application_tasks', nodeId: 'application_tasks_list' })).total, 0)
    for (const recordId of Object.values(manifest.seeded.project))
      assert.equal((await tasks({ pageId: 'project_detail', nodeId: 'project_tasks', recordId })).total, 0)
    return { tasks: 0 }
  })
  await check('负费用、缺失项目以及伪造关联范围被拒绝，业务记录没有新增', async () => {
    const cost = ac.catalog.cost
    const before = await ac.page(cost)
    const valid = {
      name: '验证拒绝保存，不应落库',
      project_id: manifest.seeded.project.building3,
      cost_date: '2026-10-02',
      category: 'MATERIAL',
      amount: 10,
      status: 'UNPAID'
    }
    const invalidAmount = await ac.request('/nocode/runtime/save', ac.saveBody(cost, { ...valid, amount: -1 }))
    assert.notEqual(invalidAmount.code, 0)
    assert.match(invalidAmount.msg, /金额|最小|小于|负|范围/)
    const missingProject = await ac.request('/nocode/runtime/save', ac.saveBody(cost, { ...valid, project_id: null }))
    assert.notEqual(missingProject.code, 0)
    assert.match(missingProject.msg, /所属项目|必填|为空|填写|引用/)
    const wrongContext = await ac.request(
      '/nocode/runtime/page',
      ac.query(cost, {
        context: { pageId: 'project_detail', nodeId: 'project_log', recordId: manifest.seeded.project.building3 }
      })
    )
    assert.notEqual(wrongContext.code, 0)
    const wrongProject = await ac.request('/nocode/runtime/save', {
      ...ac.saveBody(cost, { ...valid, project_id: manifest.seeded.project.building5 }),
      context: { pageId: 'project_detail', nodeId: 'project_cost', recordId: manifest.seeded.project.building3 }
    })
    assert.notEqual(wrongProject.code, 0)
    assert.deepEqual(await ac.page(cost), before)
    return {
      negativeAmount: invalidAmount.msg,
      missingProject: missingProject.msg,
      wrongObject: wrongContext.msg,
      wrongProject: wrongProject.msg
    }
  })
  await check('再次运行准备工具不改变发布版本、已有授权或十条示例业务记录', async () => {
    const before = await snapshot()
    const result = await new ConstructionExperience().initialize()
    assert.equal(result.applicationId, manifest.applicationId)
    assert.deepEqual(await snapshot(), before)
    return { reused: true, unchangedRecords: 10 }
  })
  await writeFile(
    resolve(OUTPUT, 'verification-http.json'),
    JSON.stringify(
      {
        batch: BATCH,
        applicationId: manifest.applicationId,
        passed: true,
        checks,
        finishedAt: new Date().toISOString()
      },
      null,
      2
    )
  )
} catch (error) {
  await writeFile(
    resolve(OUTPUT, 'verification-http.json'),
    JSON.stringify(
      {
        batch: BATCH,
        applicationId: manifest.applicationId,
        passed: false,
        checks,
        error: error.message,
        finishedAt: new Date().toISOString()
      },
      null,
      2
    )
  )
  throw error
}
