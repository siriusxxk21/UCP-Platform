import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

/** 本周/今日清单真实 HTTP 回归；默认只列矩阵，不认证、不创建任何夹具。 */
const matrix = [
  '服务端今日/本周锚点与本人、创建者、上级负责人、范围外任务权限',
  '本周选今日保留本周；移出日/周互不连带；加入今日自动补周',
  '重复操作与丢响应重试幂等；异键旧版本/同键改参数拒绝',
  '当前锚点之外的写入拒绝；切换查询日期不会移动记录',
  '新 CHECKLIST 与旧 SCHEDULE 范围和写入口隔离',
  '管理员、创建者有权查看则只读；无下级查看权的上级读取拒绝；新旧入口均不可代本人增删',
  'PERSONAL/TEAM 同一记录，员工筛选不扩大权限，批量原子拒绝',
  '领取与计划分段，计划失败不回退领取；并发仅一个版本获胜',
  '改派归档旧负责人清单，同负责人保存不清空清单',
  '截止和执行状态独立；完成保留历史，已结束清单不可写'
]
const coverageLimits = [
  '本脚本仅 HTTP；弹窗数量、按钮文案与行内子任务 UI 由前端专项/浏览器验证覆盖。',
  'CARRYOVER 通过合法查询下一日/下一周覆盖；服务端真实跨午夜的上下文历史由后端历史夹具测试覆盖，不通过改库或修改系统时间伪造。',
  '历史 MANAGER 来源现在不能经 HTTP 新建；本人移出历史项及保留来源审计由 Java 历史夹具测试覆盖，本脚本不改库伪造。',
  '只读观察沿用既有任务可见范围，不增加上级对新协议下级节点的查看权；无权读取时断言拒绝，写入校验使用负责人本人读取的合法版本与计划身份。'
]

class ChecklistAcceptance extends TaskDataPolicyAcceptance {
  constructor() {
    super()
    this.prefix = `ck${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
    this.output = resolve(process.env.TASK_CHECKLIST_OUTPUT || '.work/task-checklist', this.prefix)
    this.snapshots = []
    this.disabledUsers = []
  }

  async persist() {
    await super.persist()
    await writeFile(
      resolve(this.output, 'manifest.json'),
      JSON.stringify(
        {
          source: 'task-checklist-20261003',
          prefix: this.prefix,
          ...this.owned,
          taskIds: this.taskIds,
          retention: '仅取消本清单未结束任务并停用本清单临时员工；保留审计，不操作应用或业务记录'
        },
        null,
        2
      )
    )
    await writeFile(
      resolve(this.output, 'result.json'),
      JSON.stringify(
        {
          prefix: this.prefix,
          output: this.output,
          results: this.results,
          errors: this.errors,
          cleanup: this.cleanup,
          disabledUsers: this.disabledUsers,
          snapshots: this.snapshots,
          coverageLimits
        },
        null,
        2
      )
    )
  }

  async context(id, token, target = 'SELF') {
    assert.ok(this.taskIds.includes(id))
    const context = await this.taskApi('checklist-context', { ids: [id], target }, token)
    assert.match(context.today, /^\d{4}-\d{2}-\d{2}$/)
    assert.match(context.weekStart, /^\d{4}-\d{2}-\d{2}$/)
    assert.match(context.weekEnd, /^\d{4}-\d{2}-\d{2}$/)
    assert.ok(context.weekStart <= context.today && context.today <= context.weekEnd)
    assert.equal(context.items.length, 1)
    assert.equal(context.items[0].taskId, id)
    const item = context.items[0]
    assert.ok(Array.isArray(item.todayPlans) && Array.isArray(item.weekPlans) && Array.isArray(item.history))
    for (const plan of [...item.todayPlans, ...item.weekPlans]) assert.equal(plan.mode, 'CHECKLIST')
    return { ...context, item }
  }

  async build(id, token, period, options = {}) {
    const target = options.target || 'SELF'
    const context = await this.context(id, token, target)
    return {
      ids: [id],
      target,
      action: 'ADD',
      period,
      date: period === 'DAY' ? context.today : context.weekStart,
      expectedVersions: { [id]: context.item.version },
      requestKey: randomUUID(),
      ...options
    }
  }

  async change(id, token, period, options = {}) {
    const command = await this.build(id, token, period, options)
    const result = await this.taskApi('checklist', command, token)
    assert.deepEqual([...result.changed, ...result.unchanged], [id])
    return { command, result, context: await this.context(id, token, command.target) }
  }

  async reject(path, command, token) {
    const result = await this.taskRequest(path, command, token)
    assert.notEqual(result.code, 0, `${path} 必须返回真实拒绝，不得把越权成功当作通过`)
    assert.ok(result.http < 500, `${path} 应为业务拒绝而不是500`)
    return { code: result.code, message: result.msg }
  }

  async page(token, context, overrides = {}) {
    return this.taskApi(
      'page',
      {
        scope: 'MINE',
        tab: 'WEEK',
        date: context.weekStart,
        scheduleScope: 'PERSONAL',
        planMode: 'CHECKLIST',
        planFilter: 'PLANNED',
        search: this.prefix,
        pageNo: 1,
        pageSize: 100,
        ...overrides
      },
      token
    )
  }

  async state(id, token) {
    const row = (await this.taskApi('detail', { id }, token)).task
    return Object.fromEntries(
      [
        'status',
        'assigneeId',
        'plannedStart',
        'baselineStart',
        'baselineEnd',
        'expectedStart',
        'expectedEnd',
        'actualStart',
        'actualEnd'
      ].map(key => [key, row[key] ?? null])
    )
  }
}

const ids = plans => plans.map(plan => plan.id).sort()
const shiftDate = (date, days) => {
  const value = new Date(`${date}T12:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

async function run() {
  const ac = new ChecklistAcceptance()
  await mkdir(ac.output, { recursive: true })
  let worker, manager, personal, managed, supervised, outside, open, anchor, initialState, legacyPlanId
  try {
    const fixtures = await ac.check('01 四个独立任务及一组父子任务、两名临时员工与服务端锚点', async () => {
      await ac.login()
      await ac.prepareUsers()
      worker = ac.tokens[ac.employeeB]
      manager = ac.tokens[ac.employeeA]
      personal = (
        await ac.create(
          ac.node('个人清单', {
            assignmentMode: 'ASSIGNED',
            assigneeId: ac.employeeB,
            schedule: {
              mode: 'FIXED',
              fixedStart: null,
              fixedEnd: Date.parse('2099-12-31T18:00:00+08:00'),
              offsetDays: 0,
              durationDays: 0
            }
          }),
          {},
          worker
        )
      ).task
      managed = (
        await ac.create(
          ac.node('他人发起的个人清单', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB }),
          {},
          manager
        )
      ).task
      const subordinate = ac.node('下级个人清单', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB })
      const hierarchy = await ac.create(
        ac.node('上级负责人任务', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeA }),
        { nodes: [subordinate] }
      )
      supervised = hierarchy.nodes.find(task => task.title === subordinate.title)
      assert.ok(supervised && supervised.parentId === hierarchy.task.id, '上级负责人使用真实父子任务授权')
      outside = (await ac.create(ac.node('管理范围外', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB }))).task
      open = (await ac.create(ac.node('开放领取重试'))).task
      assert.equal(ac.taskIds.length, 6)
      anchor = await ac.context(personal.id, worker)
      assert.equal(anchor.item.canAdd, true)
      assert.deepEqual(anchor.item.todayPlans, [])
      assert.deepEqual(anchor.item.weekPlans, [])
      initialState = await ac.state(personal.id, worker)
      assert.ok(initialState.expectedEnd)
      assert.equal(initialState.status, 'PENDING')
      return {
        today: anchor.today,
        weekStart: anchor.weekStart,
        weekEnd: anchor.weekEnd,
        tasks: ac.taskIds,
        employees: [ac.employeeA, ac.employeeB],
        applications: 0,
        objects: 0
      }
    })
    const week = await ac.check(
      '02 加本周后选今日保留原周身份',
      async () => {
        const first = await ac.change(personal.id, worker, 'WEEK')
        assert.equal(first.context.item.weekPlans.length, 1)
        assert.equal(first.context.item.todayPlans.length, 0)
        const next = await ac.change(personal.id, worker, 'DAY')
        assert.equal(next.context.item.todayPlans.length, 1)
        assert.deepEqual(ids(next.context.item.weekPlans), ids(first.context.item.weekPlans))
        assert.deepEqual(await ac.state(personal.id, worker), initialState)
        return { today: ids(next.context.item.todayPlans), week: ids(next.context.item.weekPlans) }
      },
      [fixtures]
    )
    const independent = await ac.check(
      '03 移出今日保周，移出周保今日且周查询不得由日反投影',
      async () => {
        let current = await ac.context(personal.id, worker)
        const weekly = ids(current.item.weekPlans)
        await ac.change(personal.id, worker, 'DAY', { action: 'REMOVE', planIds: ids(current.item.todayPlans) })
        current = await ac.context(personal.id, worker)
        assert.equal(current.item.todayPlans.length, 0)
        assert.deepEqual(ids(current.item.weekPlans), weekly)
        await ac.change(personal.id, worker, 'DAY')
        current = await ac.context(personal.id, worker)
        const daily = ids(current.item.todayPlans)
        await ac.change(personal.id, worker, 'WEEK', { action: 'REMOVE', planIds: ids(current.item.weekPlans) })
        current = await ac.context(personal.id, worker)
        assert.deepEqual(ids(current.item.todayPlans), daily)
        assert.equal(current.item.weekPlans.length, 0)
        assert.ok(!(await ac.page(worker, anchor)).list.some(task => task.id === personal.id))
        assert.ok(
          (await ac.page(worker, anchor, { tab: 'TODAY', date: anchor.today })).list.some(
            task => task.id === personal.id
          )
        )
        assert.ok(
          (await ac.page(worker, anchor, { planFilter: 'UNPLANNED' })).list.some(task => task.id === personal.id)
        )
        return { daily, weeklyRemoved: weekly }
      },
      [week]
    )
    const restore = await ac.check(
      '04 再加已在今日的任务也补缺失周；重复添加不产生新记录',
      async () => {
        const before = await ac.context(personal.id, worker)
        const restored = await ac.change(personal.id, worker, 'DAY')
        assert.deepEqual(ids(restored.context.item.todayPlans), ids(before.item.todayPlans))
        assert.equal(restored.context.item.weekPlans.length, 1)
        const duplicate = await ac.change(personal.id, worker, 'DAY')
        assert.deepEqual(duplicate.result, { changed: [], unchanged: [personal.id] })
        assert.equal(duplicate.context.item.version, restored.context.item.version)
        assert.deepEqual(ids(duplicate.context.item.weekPlans), ids(restored.context.item.weekPlans))
        assert.deepEqual(await ac.state(personal.id, worker), initialState)
        return duplicate.context.item
      },
      [independent]
    )
    await ac.check(
      '05 回执幂等与异键旧版本冲突，同键改参不能覆盖',
      async () => {
        const current = await ac.context(personal.id, worker)
        const command = await ac.build(personal.id, worker, 'DAY', {
          action: 'REMOVE',
          planIds: ids(current.item.todayPlans)
        })
        const first = await ac.taskApi('checklist', command, worker)
        const after = await ac.context(personal.id, worker)
        const replay = await ac.taskApi('checklist', command, worker)
        assert.deepEqual(replay, first, '丢响应重试必须返回原回执')
        assert.equal((await ac.context(personal.id, worker)).item.version, after.item.version)
        const stale = await ac.reject('checklist', { ...command, requestKey: randomUUID() }, worker)
        const changedKey = await ac.reject(
          'checklist',
          { ...command, action: 'ADD', planIds: undefined, expectedVersions: { [personal.id]: after.item.version } },
          worker
        )
        assert.deepEqual((await ac.context(personal.id, worker)).item, after.item)
        await ac.change(personal.id, worker, 'DAY')
        return { stale, changedKey }
      },
      [restore]
    )
    await ac.check(
      '06 旧日周锚点与新版MONTH/COARSE拒绝，切换查询不搬移',
      async () => {
        const before = await ac.context(personal.id, worker)
        const errors = []
        for (const [period, date] of [
          ['DAY', shiftDate(anchor.today, -1)],
          ['WEEK', shiftDate(anchor.weekStart, -7)]
        ])
          errors.push(await ac.reject('checklist', await ac.build(personal.id, worker, period, { date }), worker))
        errors.push(
          await ac.reject('checklist', await ac.build(personal.id, worker, 'MONTH', { date: anchor.today }), worker)
        )
        const invalidQuery = {
          scope: 'MINE',
          scheduleScope: 'PERSONAL',
          planMode: 'CHECKLIST',
          date: anchor.today,
          pageNo: 1,
          pageSize: 10
        }
        errors.push(await ac.reject('page', { ...invalidQuery, tab: 'MONTH' }, worker))
        errors.push(await ac.reject('page', { ...invalidQuery, tab: 'WEEK', planFilter: 'COARSE' }, worker))
        assert.ok(
          !(await ac.page(worker, anchor, { tab: 'TODAY', date: shiftDate(anchor.today, 1) })).list.some(
            task => task.id === personal.id
          )
        )
        assert.ok(
          !(await ac.page(worker, anchor, { date: shiftDate(anchor.weekStart, 7) })).list.some(
            task => task.id === personal.id
          )
        )
        for (const query of [
          { tab: 'TODAY', date: shiftDate(anchor.today, 1) },
          { tab: 'WEEK', date: shiftDate(anchor.weekStart, 7) }
        ])
          assert.ok(
            (await ac.page(worker, anchor, { ...query, planFilter: 'CARRYOVER' })).list.some(
              task => task.id === personal.id
            ),
            '之前未完成只引用原清单，不自动创建查询期间的清单'
          )
        assert.deepEqual((await ac.context(personal.id, worker)).item, before.item)
        return { errors, carryover: '合法查询下一日/周返回原未完成任务，当前清单身份不变' }
      },
      [restore]
    )
    const legacy = await ac.check(
      '07 旧SCHEDULE与新清单并存但查询/取消互不越界',
      async () => {
        const context = await ac.taskApi('plan-context', { ids: [personal.id], target: 'SELF' }, worker)
        const before = await ac.context(personal.id, worker)
        await ac.taskApi(
          'schedule',
          {
            ids: [personal.id],
            target: 'SELF',
            action: 'ARRANGE',
            period: 'MONTH',
            date: '2000-01-01',
            expectedVersions: { [personal.id]: context.items[0].version }
          },
          worker
        )
        const old = (await ac.taskApi('plan-context', { ids: [personal.id], target: 'SELF' }, worker)).items[0]
        assert.equal(old.plans.length, 1)
        assert.equal(old.plans[0].mode, 'SCHEDULE')
        legacyPlanId = old.plans[0].id
        const after = await ac.context(personal.id, worker)
        assert.deepEqual(ids(after.item.todayPlans), ids(before.item.todayPlans))
        assert.deepEqual(ids(after.item.weekPlans), ids(before.item.weekPlans))
        assert.ok(after.item.history.some(plan => plan.id === legacyPlanId && plan.mode === 'SCHEDULE'))
        const deniedChecklist = await ac.reject(
          'checklist',
          await ac.build(personal.id, worker, 'WEEK', { action: 'REMOVE', planIds: [legacyPlanId] }),
          worker
        )
        const deniedOld = await ac.reject(
          'schedule',
          {
            ids: [personal.id],
            target: 'SELF',
            action: 'CANCEL',
            planIds: ids(after.item.weekPlans),
            expectedVersions: { [personal.id]: after.item.version }
          },
          worker
        )
        assert.ok(
          !(await ac.page(worker, anchor, { tab: 'TODAY', date: '2000-01-01' })).list.some(
            task => task.id === personal.id
          )
        )
        assert.ok(
          !(await ac.page(worker, anchor, { planFilter: 'CARRYOVER' })).list.some(task => task.id === personal.id)
        )
        return { legacyPlanId, deniedChecklist, deniedOld }
      },
      [restore]
    )
    const readonlyObservers = await ac.check(
      '08 管理员和创建者只读、范围外上级读拒绝，所有新旧入口均拒绝代增删',
      async () => {
        for (const task of [managed, supervised]) {
          await ac.change(task.id, worker, 'DAY')
          const oldContext = await ac.taskApi('plan-context', { ids: [task.id], target: 'SELF' }, worker)
          await ac.taskApi(
            'schedule',
            {
              ids: [task.id],
              target: 'SELF',
              action: 'ARRANGE',
              period: 'MONTH',
              date: '2001-01-01',
              expectedVersions: { [task.id]: oldContext.items[0].version }
            },
            worker
          )
        }
        const denials = []
        for (const { role, token, task, canRead } of [
          { role: '管理员', token: ac.tokens.admin, task: managed, canRead: true },
          { role: '任务创建者', token: manager, task: managed, canRead: true },
          { role: '上级负责人', token: manager, task: supervised, canRead: false }
        ]) {
          const before = await ac.context(task.id, worker)
          const oldBefore = (await ac.taskApi('plan-context', { ids: [task.id], target: 'SELF' }, worker)).items[0]
          assert.equal(before.item.canAdd, true)
          assert.equal(before.item.todayPlans.length, 1)
          assert.equal(before.item.weekPlans.length, 1)
          for (const plan of [...before.item.todayPlans, ...before.item.weekPlans]) {
            assert.equal(plan.source, 'SELF')
            assert.equal(plan.canCancel, true)
          }
          assert.equal(oldBefore.plans.length, 1)
          if (canRead) {
            const readonly = await ac.context(task.id, token, 'ASSIGNEE')
            assert.equal(readonly.item.canAdd, false)
            for (const plan of [...readonly.item.todayPlans, ...readonly.item.weekPlans])
              assert.equal(plan.canCancel, false)
            const oldReadonly = (await ac.taskApi('plan-context', { ids: [task.id], target: 'ASSIGNEE' }, token))
              .items[0]
            assert.equal(oldReadonly.readOnly, true)
            assert.equal(oldReadonly.canArrange, false)
            assert.equal(oldReadonly.canCancel, false)
            assert.equal((await ac.taskApi('detail', { id: task.id }, token)).task.canPlan, false)
            ac.snapshots.push({ name: `${role}只读本人清单`, taskId: task.id, item: readonly.item })
          } else {
            const readDenials = []
            for (const path of ['checklist-context', 'plan-context'])
              readDenials.push({
                path,
                ...(await ac.reject(path, { ids: [task.id], target: 'ASSIGNEE' }, token))
              })
            ac.snapshots.push({ name: `${role}沿既有范围拒绝读取下级计划`, taskId: task.id, readDenials })
          }
          const denialStart = denials.length
          for (const target of ['SELF', 'ASSIGNEE']) {
            const expectedVersions = { [task.id]: before.item.version }
            for (const action of ['ADD', 'REMOVE'])
              denials.push({
                role,
                target,
                path: 'checklist',
                action,
                ...(await ac.reject(
                  'checklist',
                  {
                    ids: [task.id],
                    target,
                    action,
                    period: 'DAY',
                    date: anchor.today,
                    ...(action === 'REMOVE' ? { planIds: ids(before.item.todayPlans) } : {}),
                    expectedVersions,
                    requestKey: randomUUID()
                  },
                  token
                ))
              })
            for (const action of ['ARRANGE', 'CANCEL'])
              denials.push({
                role,
                target,
                path: 'schedule',
                action,
                ...(await ac.reject(
                  'schedule',
                  {
                    ids: [task.id],
                    target,
                    action,
                    period: 'MONTH',
                    date: '2001-01-01',
                    ...(action === 'CANCEL' ? { planIds: ids(oldBefore.plans) } : {}),
                    expectedVersions
                  },
                  token
                ))
              })
            for (const include of [true, false])
              denials.push({
                role,
                target,
                path: 'plan',
                include,
                ...(await ac.reject(
                  'plan',
                  { ids: [task.id], target, period: 'MONTH', date: '2001-01-01', include },
                  token
                ))
              })
          }
          assert.deepEqual((await ac.context(task.id, worker)).item, before.item, `${role}代写拒绝后本人清单完全不变`)
          assert.deepEqual(
            (await ac.taskApi('plan-context', { ids: [task.id], target: 'SELF' }, worker)).items[0],
            oldBefore,
            `${role}代写拒绝后旧排期完全不变`
          )
          ac.snapshots.push({
            name: `${role}各入口代写拒绝且本人记录不变`,
            taskId: task.id,
            denials: denials.slice(denialStart)
          })
        }
        return { denials, checks: denials.length, historicalManagerItems: '由Java历史夹具测试覆盖，不通过HTTP伪造' }
      },
      [fixtures]
    )
    await ac.check(
      '09 TEAM同记录且只在原管理边界，PERSONAL不能伪装员工',
      async () => {
        const team = await ac.page(manager, anchor, {
          scope: 'MANAGE',
          scheduleScope: 'TEAM',
          assigneeId: ac.employeeB,
          rootsOnly: false
        })
        assert.ok(team.list.some(task => task.id === managed.id))
        assert.ok(!team.list.some(task => task.id === personal.id || task.id === outside.id))
        assert.ok(!team.list.some(task => task.id === supervised.id), 'TEAM查询不能增加原本不可见的下级节点权限')
        const mine = await ac.page(worker, anchor)
        assert.equal(mine.list.filter(task => task.id === managed.id).length, 1, '同一清单不能让列表重复行')
        for (const task of team.list) assert.equal(task.canPlan, false)
        const weekIds = ids((await ac.context(managed.id, worker)).item.weekPlans)
        assert.deepEqual(
          ids(
            team.list
              .find(task => task.id === managed.id)
              .plans.filter(plan => plan.mode === 'CHECKLIST' && plan.period === 'WEEK')
          ),
          weekIds
        )
        const personalDenied = await ac.reject(
          'page',
          {
            scope: 'MINE',
            tab: 'WEEK',
            date: anchor.weekStart,
            planMode: 'CHECKLIST',
            scheduleScope: 'PERSONAL',
            assigneeId: ac.employeeB,
            pageNo: 1,
            pageSize: 10
          },
          manager
        )
        const contextDenied = await ac.reject('checklist-context', { ids: [outside.id], target: 'ASSIGNEE' }, manager)
        return { personalDenied, contextDenied, team: team.list.map(task => task.id) }
      },
      [readonlyObservers]
    )
    await ac.check(
      '10 混合越权/旧版本整批回滚，合法批次区分新增与原有项',
      async () => {
        const good = await ac.context(managed.id, manager, 'ASSIGNEE')
        const other = await ac.context(outside.id, ac.tokens.admin, 'ASSIGNEE')
        const denied = await ac.reject(
          'checklist',
          {
            ids: [managed.id, outside.id],
            target: 'ASSIGNEE',
            action: 'ADD',
            period: 'DAY',
            date: anchor.today,
            expectedVersions: { [managed.id]: good.item.version, [outside.id]: other.item.version },
            requestKey: randomUUID()
          },
          manager
        )
        assert.deepEqual((await ac.context(managed.id, manager, 'ASSIGNEE')).item, good.item)
        const beforeA = await ac.context(personal.id, worker)
        const beforeB = await ac.context(outside.id, worker)
        const stale = await ac.reject(
          'checklist',
          {
            ids: [personal.id, outside.id],
            target: 'SELF',
            action: 'ADD',
            period: 'DAY',
            date: anchor.today,
            expectedVersions: {
              [personal.id]: Math.max(0, beforeA.item.version - 1),
              [outside.id]: beforeB.item.version
            },
            requestKey: randomUUID()
          },
          worker
        )
        assert.deepEqual((await ac.context(outside.id, worker)).item, beforeB.item)
        assert.deepEqual((await ac.context(personal.id, worker)).item, beforeA.item)
        const success = await ac.taskApi(
          'checklist',
          {
            ids: [personal.id, outside.id],
            target: 'SELF',
            action: 'ADD',
            period: 'WEEK',
            date: anchor.weekStart,
            expectedVersions: {
              [personal.id]: beforeA.item.version,
              [outside.id]: beforeB.item.version
            },
            requestKey: randomUUID()
          },
          worker
        )
        assert.deepEqual(success, { changed: [outside.id], unchanged: [personal.id] })
        assert.equal((await ac.context(outside.id, worker)).item.weekPlans.length, 1)
        assert.deepEqual((await ac.context(personal.id, worker)).item, beforeA.item)
        return { denied, stale, success }
      },
      [readonlyObservers, restore]
    )
    await ac.check(
      '11 REMOVE混入错误任务/错误期间ID不能部分删除',
      async () => {
        const before = await ac.context(personal.id, worker)
        const wrongOwner = await ac.context(managed.id, worker)
        const denied = await ac.reject(
          'checklist',
          await ac.build(personal.id, worker, 'DAY', {
            action: 'REMOVE',
            planIds: [...ids(before.item.todayPlans), ...ids(wrongOwner.item.todayPlans)]
          }),
          worker
        )
        assert.deepEqual((await ac.context(personal.id, worker)).item, before.item)
        const periodDenied = await ac.reject(
          'checklist',
          await ac.build(personal.id, worker, 'DAY', { action: 'REMOVE', planIds: ids(before.item.weekPlans) }),
          worker
        )
        assert.deepEqual((await ac.context(personal.id, worker)).item, before.item)
        return { denied, periodDenied }
      },
      [readonlyObservers, restore]
    )
    const claimed = await ac.check(
      '12 领取回执重试不重复；计划失败不撤销领取、不开始任务',
      async () => {
        const row = (await ac.taskApi('detail', { id: open.id })).task
        const command = { id: row.id, expectedRevision: row.revision, requestKey: randomUUID() }
        const first = await ac.taskApi('claim', command, worker)
        const replay = await ac.taskApi('claim', command, worker)
        assert.equal(first.task.id, replay.task.id)
        assert.equal(replay.task.status, 'PENDING')
        assert.equal(String(replay.task.assigneeId), ac.employeeB)
        assert.equal(replay.task.actualStart, null)
        const invalid = await ac.reject(
          'checklist',
          await ac.build(open.id, worker, 'DAY', { date: shiftDate(anchor.today, -1) }),
          worker
        )
        const detail = await ac.taskApi('detail', { id: open.id }, worker)
        assert.equal(detail.events.filter(event => event.type === 'CLAIMED').length, 1)
        assert.equal(detail.task.status, 'PENDING')
        assert.equal(String(detail.task.assigneeId), ac.employeeB)
        assert.equal((await ac.context(open.id, worker)).item.todayPlans.length, 0)
        return { claimReplay: true, realClaimEvents: 1, invalid }
      },
      [fixtures]
    )
    const concurrent = await ac.check(
      '13 同版本并发添加只有一个成功，重放成功key不重复记录',
      async () => {
        const command = await ac.build(open.id, worker, 'DAY')
        const second = { ...command, requestKey: randomUUID() }
        const responses = await Promise.all([
          ac.taskRequest('checklist', command, worker),
          ac.taskRequest('checklist', second, worker)
        ])
        assert.equal(responses.filter(response => response.code === 0).length, 1)
        assert.ok(responses.every(response => response.http < 500))
        const success = responses.findIndex(response => response.code === 0)
        const accepted = success === 0 ? command : second
        const replay = await ac.taskApi('checklist', accepted, worker)
        assert.deepEqual(replay, responses[success].data)
        const after = await ac.context(open.id, worker)
        assert.equal(after.item.todayPlans.length, 1)
        assert.equal(after.item.weekPlans.length, 1)
        assert.equal((await ac.state(open.id, worker)).status, 'PENDING')
        return {
          codes: responses.map(response => response.code),
          today: ids(after.item.todayPlans),
          week: ids(after.item.weekPlans)
        }
      },
      [claimed]
    )
    await ac.check(
      '14 改派归档旧员工日周，不带给新员工；同负责人保存不归档',
      async () => {
        const before = await ac.context(open.id, worker)
        const oldIds = [...ids(before.item.todayPlans), ...ids(before.item.weekPlans)]
        let row = (await ac.taskApi('detail', { id: open.id })).task
        await ac.taskApi('assign', {
          id: open.id,
          expectedRevision: row.revision,
          assignmentMode: 'ASSIGNED',
          assigneeId: ac.employeeA,
          candidateUserIds: [],
          requestKey: randomUUID()
        })
        const next = await ac.context(open.id, manager)
        assert.equal(next.item.todayPlans.length, 0)
        assert.equal(next.item.weekPlans.length, 0)
        const history = await ac.context(open.id, ac.tokens.admin, 'ASSIGNEE')
        for (const id of oldIds)
          assert.ok(history.item.history.some(plan => plan.id === id && plan.historyReason === 'REASSIGNED'))
        await ac.change(open.id, manager, 'DAY')
        const own = await ac.context(open.id, manager)
        row = (await ac.taskApi('detail', { id: open.id })).task
        await ac.taskApi('assign', {
          id: open.id,
          expectedRevision: row.revision,
          assignmentMode: 'ASSIGNED',
          assigneeId: ac.employeeA,
          candidateUserIds: [],
          requestKey: randomUUID()
        })
        const same = await ac.context(open.id, manager)
        assert.deepEqual(ids(same.item.todayPlans), ids(own.item.todayPlans))
        assert.deepEqual(ids(same.item.weekPlans), ids(own.item.weekPlans))
        return { oldIds, currentAssignee: ac.employeeA }
      },
      [concurrent]
    )
    await ac.check(
      '15 清单与任务日期/状态独立，完成后历史仍在但不可增删',
      async () => {
        assert.deepEqual(await ac.state(personal.id, worker), initialState)
        const before = await ac.context(personal.id, worker)
        await ac.command(personal.id, 'START', worker)
        await ac.command(personal.id, 'COMPLETE', worker)
        const state = await ac.state(personal.id, worker)
        assert.equal(state.status, 'COMPLETED')
        assert.ok(state.actualStart && state.actualEnd)
        assert.equal(state.expectedEnd, initialState.expectedEnd)
        const after = await ac.context(personal.id, worker)
        assert.equal(after.item.canAdd, false)
        assert.deepEqual(ids(after.item.todayPlans), ids(before.item.todayPlans))
        assert.deepEqual(ids(after.item.weekPlans), ids(before.item.weekPlans))
        for (const plan of [...after.item.todayPlans, ...after.item.weekPlans]) assert.equal(plan.canCancel, false)
        assert.ok(
          (await ac.page(worker, anchor, { tab: 'TODAY', date: anchor.today })).list.some(
            task => task.id === personal.id
          )
        )
        const denied = await ac.reject('checklist', await ac.build(personal.id, worker, 'DAY'), worker)
        return { state, denied, legacyPlanId }
      },
      [legacy, restore]
    )
  } catch (error) {
    ac.errors.push(error.stack || error.message)
  } finally {
    const adminToken = ac.tokens.admin
    await ac.finish()
    for (const user of ac.owned.users) {
      const after = await ac.api(`/system/user/get?id=${user.id}`, undefined, adminToken).catch(() => null)
      const verified = after?.username === user.username && Number(after.status) === 1
      ac.disabledUsers.push({ ...user, disabledVerified: verified })
      if (!verified) ac.errors.push(`停用复核失败：${user.id}`)
    }
    await ac.persist()
  }
  const summary = {
    output: ac.output,
    passed: ac.results.filter(item => item.status === 'passed').length,
    failed: ac.results.filter(item => item.status === 'failed').length,
    blocked: ac.results.filter(item => item.status === 'blocked').length,
    cleanup: ac.cleanup,
    disabledUsers: ac.disabledUsers,
    coverageLimits
  }
  console.log(JSON.stringify(summary, null, 2))
  if (summary.failed || summary.blocked || ac.errors.length || ac.cleanup.some(item => item.status === 'failed'))
    process.exitCode = 1
}

if (process.env.TASK_CHECKLIST_WRITE_RUN === '1') await run()
else
  console.log(
    JSON.stringify(
      {
        mode: 'prepare-only',
        writes: 0,
        fixtures: '4独立任务及1组父子任务（共6任务）、2临时员工、1角色；不创建应用或业务数据',
        matrix,
        coverageLimits
      },
      null,
      2
    )
  )
