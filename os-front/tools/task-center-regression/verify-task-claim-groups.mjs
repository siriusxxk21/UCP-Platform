import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

// 只走正式 HTTP；默认准备模式，不认证、不建夹具。写入仅限本次精确登记身份。
if (process.env.TASK_CLAIM_GROUPS_RUN !== '1') {
  console.log(
    '准备验证：真实归组、部分领取、旧预览冲突、整项承接、受限分工、并发及同键恢复、首次分配和旧安排保护。设置 TASK_CLAIM_GROUPS_RUN=1 后运行。'
  )
} else {
  const ac = new TaskDataPolicyAcceptance()
  ac.prefix = `cg${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
  ac.output = resolve('.work/task-claim-groups', ac.prefix)
  const api = (path, body, token) => ac.taskApi(path, body, token)
  const req = (path, body, token) => ac.taskRequest(path, body, token)
  const own = id => assert.ok(ac.taskIds.includes(id), '只操作本次登记任务')
  const query = { search: ac.prefix, pageNo: 1, pageSize: 100 }
  const detail = id => api('detail', { id })
  const reject = result => {
    assert.notEqual(result.code, 0, '必须拒绝冲突或越权')
    assert.ok(result.http < 500, '业务拒绝不能变成未处理错误')
  }
  let tokenA, tokenB, main, a, b, open, pending, other, stale, claimed, race, assigned
  try {
    await ac.login()
    await ac.prepareUsers()
    tokenA = ac.tokens[ac.employeeA]
    tokenB = ac.tokens[ac.employeeB]

    const setup = await ac.check('真实根归组与安全领取摘要', async () => {
      const root = ac.node('整项')
      a = ac.node('默认A', { assignmentMode: 'FOLLOW_ROOT' })
      b = ac.node('默认B', { assignmentMode: 'FOLLOW_ROOT', predecessorIds: [a.id] })
      open = ac.node('明确开放', { candidateUserIds: [ac.employeeB] })
      pending = ac.node('待指定', { assignmentMode: 'UNASSIGNED' })
      other = ac.node('同事已有分工', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB })
      main = await ac.create(root, { nodes: [a, b, open, pending, other] })
      // 创建实例会把设计节点 ID 映射为运行节点 ID，后续命令只使用正式回执中的身份。
      for (const node of [a, b, open, pending, other]) {
        const created = main.nodes.find(item => item.title === node.title)
        assert.ok(created)
        node.id = created.id
      }
      const page = await api('claimable-groups', query, tokenA)
      assert.equal(page.total, 1)
      assert.equal(page.list[0].rootId, main.task.id)
      assert.equal(page.list[0].canClaimGroup, true)
      const children = await api('claimable-children', { rootId: main.task.id }, tokenA)
      assert.ok(children.every(item => item.rootId === main.task.id))
      assert.ok(children.some(item => item.id === a.id))
      assert.ok(!children.some(item => [open.id, pending.id, other.id].includes(item.id)))
      assert.ok(children.every(item => !('candidateUserIds' in item) && !('binding' in item)))
      stale = await api('claim-preview', { rootId: main.task.id }, tokenA)
      assert.deepEqual(new Set(stale.items.map(item => item.id)), new Set([main.task.id, a.id, b.id]))
      return { rootId: main.task.id, previewCount: stale.items.length }
    })

    const partial = await ac.check(
      '仅领一项不占总任务且使旧整项预览失效',
      async () => {
        own(a.id)
        const current = (await detail(a.id)).task
        await api('claim', { id: a.id, expectedRevision: current.revision, requestKey: randomUUID() }, tokenA)
        const state = await detail(main.task.id)
        assert.equal(state.task.assigneeId, null)
        assert.equal(String(state.nodes.find(item => item.id === a.id).assigneeId), ac.employeeA)
        assert.equal(state.nodes.find(item => item.id === b.id).assigneeId, null)
        reject(
          await req(
            'claim-group',
            {
              rootId: main.task.id,
              expectedInstanceRevision: stale.instanceRevision,
              requestKey: randomUUID()
            },
            tokenA
          )
        )
        return { singleId: a.id, rootStillUnassigned: true }
      },
      [setup]
    )

    const whole = await ac.check(
      '整项原子承接、同键恢复且不启动不加入计划',
      async () => {
        own(main.task.id)
        const preview = await api('claim-preview', { rootId: main.task.id }, tokenA)
        assert.deepEqual(new Set(preview.items.map(item => item.id)), new Set([main.task.id, b.id]))
        claimed = { rootId: main.task.id, expectedInstanceRevision: preview.instanceRevision, requestKey: randomUUID() }
        await api('claim-group', claimed, tokenA)
        await api('claim-group', claimed, tokenA)
        const state = await detail(main.task.id)
        for (const id of [main.task.id, a.id, b.id]) {
          const item = state.nodes.find(node => node.id === id) || state.task
          assert.equal(String(item.assigneeId), ac.employeeA)
          assert.equal(item.status, 'PENDING')
          assert.equal(item.actualStart, null)
          assert.ok(!item.plans?.length)
        }
        assert.equal(state.nodes.find(item => item.id === open.id).assigneeId, null)
        assert.equal(state.nodes.find(item => item.id === pending.id).assigneeId, null)
        assert.equal(String(state.nodes.find(item => item.id === other.id).assigneeId), ac.employeeB)
        reject(
          await req(
            'claim-group',
            { ...claimed, expectedInstanceRevision: claimed.expectedInstanceRevision + 1 },
            tokenA
          )
        )
        return { received: [main.task.id, b.id], preserved: [a.id, open.id, pending.id, other.id] }
      },
      [partial]
    )

    await ac.check(
      '总负责人有限分工、原详情权限不扩大',
      async () => {
        own(b.id)
        const mine = await api('detail', { id: b.id }, tokenA)
        assert.equal(mine.task.canDelegate, true)
        const command = {
          id: b.id,
          expectedRevision: mine.task.revision,
          assignmentMode: 'ASSIGNED',
          assigneeId: ac.employeeB,
          candidateUserIds: [],
          requestKey: randomUUID()
        }
        await api('assign', command, tokenA)
        await api('assign', command, tokenA)
        assert.equal(String((await detail(b.id)).task.assigneeId), ac.employeeB)
        const ownA = (await detail(a.id)).task
        reject(
          await req(
            'assign',
            { ...command, id: a.id, expectedRevision: ownA.revision, requestKey: randomUUID() },
            tokenB
          )
        )
        const colleagues = (await detail(other.id)).task
        reject(
          await req(
            'assign',
            { ...command, id: other.id, expectedRevision: colleagues.revision, requestKey: randomUUID() },
            tokenA
          )
        )
        const pages = await api('claimable-groups', query, tokenB)
        const group = pages.list.find(item => item.rootId === main.task.id)
        assert.ok(group)
        assert.equal(group.canClaimGroup, false)
        const leaves = await api('claimable-children', { rootId: main.task.id }, tokenB)
        assert.deepEqual(
          leaves.map(item => item.id),
          [open.id]
        )
        return { delegatedId: b.id, remainingOpen: open.id }
      },
      [whole]
    )

    await ac.check('两人并发只有一人整项成功且赢家同键可恢复', async () => {
      const c = ac.node('竞争C', { assignmentMode: 'FOLLOW_ROOT' })
      const d = ac.node('竞争D', { assignmentMode: 'FOLLOW_ROOT' })
      race = await ac.create(ac.node('竞争整项'), { nodes: [c, d] })
      own(race.task.id)
      const p = await api('claim-preview', { rootId: race.task.id }, tokenA)
      const commands = [tokenA, tokenB].map(() => ({
        rootId: race.task.id,
        expectedInstanceRevision: p.instanceRevision,
        requestKey: randomUUID()
      }))
      const results = await Promise.all([tokenA, tokenB].map((token, i) => req('claim-group', commands[i], token)))
      assert.equal(results.filter(result => result.code === 0).length, 1)
      const index = results.findIndex(result => result.code === 0)
      reject(results[1 - index])
      const winner = [ac.employeeA, ac.employeeB][index]
      const state = await detail(race.task.id)
      assert.ok(state.nodes.every(item => String(item.assigneeId) === winner))
      await api('claim-group', commands[index], [tokenA, tokenB][index])
      return { rootId: race.task.id, winner, nodes: state.nodes.length }
    })

    await ac.check('根首次分配接收默认项，后续换人不静默改派', async () => {
      const child = ac.node('首分配默认项', { assignmentMode: 'FOLLOW_ROOT' })
      const legacy = ac.node('旧开放项')
      assigned = await ac.create(ac.node('首次分配'), { nodes: [child, legacy] })
      child.id = assigned.nodes.find(item => item.title === child.title).id
      legacy.id = assigned.nodes.find(item => item.title === legacy.title).id
      own(assigned.task.id)
      const assign = userId => ({
        id: assigned.task.id,
        expectedRevision: assigned.task.revision,
        assignmentMode: 'ASSIGNED',
        assigneeId: userId,
        candidateUserIds: [],
        requestKey: randomUUID()
      })
      await api('assign', assign(ac.employeeA))
      assigned = await detail(assigned.task.id)
      assert.equal(String(assigned.nodes.find(item => item.id === child.id).assigneeId), ac.employeeA)
      assert.equal(assigned.nodes.find(item => item.id === legacy.id).assigneeId, null)
      await api('assign', assign(ac.employeeB))
      const state = await detail(assigned.task.id)
      assert.equal(String(state.task.assigneeId), ac.employeeB)
      assert.equal(String(state.nodes.find(item => item.id === child.id).assigneeId), ac.employeeA)
      return { rootId: state.task.id, childPreserved: child.id, legacyPreserved: legacy.id }
    })
    await ac.check('一次领取旧开放子任务，保护其他安排且可继续分工', async () => {
      const openA = ac.node('整体开放A')
      const openB = ac.node('整体开放B', { predecessorIds: [openA.id] })
      const restricted = ac.node('整体受限', { candidateUserIds: [ac.employeeB] })
      const colleague = ac.node('整体同事', { assignmentMode: 'ASSIGNED', assigneeId: ac.employeeB })
      const unassigned = ac.node('整体待分配', { assignmentMode: 'UNASSIGNED' })
      const task = await ac.create(ac.node('一次领取整个任务'), {
        nodes: [openA, openB, restricted, colleague, unassigned]
      })
      own(task.task.id)
      const titleId = n => task.nodes.find(item => item.title === n.title).id
      const expected = new Set([task.task.id, titleId(openA), titleId(openB)])
      const group = (await api('claimable-groups', query, tokenA)).list.find(item => item.rootId === task.task.id)
      assert.equal(group.wholeClaimCount, 3)
      const preview = await api('claim-preview', { rootId: task.task.id, includeOpen: true }, tokenA)
      assert.deepEqual(new Set(preview.items.map(item => item.id)), expected)
      const body = {
        rootId: task.task.id,
        expectedInstanceRevision: preview.instanceRevision,
        requestKey: randomUUID(),
        includeOpen: true
      }
      await api('claim-group', body, tokenA)
      await api('claim-group', body, tokenA)
      reject(await req('claim-group', { ...body, includeOpen: false }, tokenA))
      const state = await detail(task.task.id)
      for (const item of state.nodes.filter(n => expected.has(n.id))) {
        assert.equal(String(item.assigneeId), ac.employeeA)
        assert.equal(item.status, 'PENDING')
        assert.equal(item.actualStart, null)
        assert.ok(!item.plans?.length)
      }
      assert.equal(state.nodes.find(n => n.id === titleId(restricted)).assigneeId, null)
      assert.equal(state.nodes.find(n => n.id === titleId(unassigned)).assigneeId, null)
      assert.equal(String(state.nodes.find(n => n.id === titleId(colleague)).assigneeId), ac.employeeB)
      const child = (await api('detail', { id: titleId(openB) }, tokenA)).task
      assert.equal(child.canDelegate, true)
      await api(
        'assign',
        {
          id: child.id,
          expectedRevision: child.revision,
          assignmentMode: 'ASSIGNED',
          assigneeId: ac.employeeB,
          candidateUserIds: [],
          requestKey: randomUUID()
        },
        tokenA
      )
      assert.equal(String((await detail(child.id)).task.assigneeId), ac.employeeB)
      return { rootId: task.task.id, claimedCount: expected.size, delegatedId: child.id }
    })

    await ac.check('两人并发领取整个开放任务只能一个成功，未知结果同键恢复', async () => {
      const task = await ac.create(ac.node('整个开放竞争'), { nodes: [ac.node('开放竞争A'), ac.node('开放竞争B')] })
      own(task.task.id)
      const preview = await api('claim-preview', { rootId: task.task.id, includeOpen: true }, tokenA)
      assert.equal(preview.items.length, 3)
      const bodies = [tokenA, tokenB].map(() => ({
        rootId: task.task.id,
        expectedInstanceRevision: preview.instanceRevision,
        requestKey: randomUUID(),
        includeOpen: true
      }))
      const results = await Promise.all([tokenA, tokenB].map((token, i) => req('claim-group', bodies[i], token)))
      assert.equal(results.filter(result => result.code === 0).length, 1)
      const winner = results.findIndex(result => result.code === 0)
      reject(results[1 - winner])
      await api('claim-group', bodies[winner], [tokenA, tokenB][winner])
      const state = await detail(task.task.id)
      assert.equal(state.nodes.length, 3)
      assert.ok(state.nodes.every(item => String(item.assigneeId) === [ac.employeeA, ac.employeeB][winner]))
      return { rootId: task.task.id, nodes: state.nodes.length, winner: [ac.employeeA, ac.employeeB][winner] }
    })
  } catch (error) {
    ac.errors.push(error.message)
    console.error(error.message)
  } finally {
    if (ac.tokens.admin) await ac.finish()
    await writeFile(
      resolve(ac.output, 'result.json'),
      JSON.stringify(
        {
          prefix: ac.prefix,
          results: ac.results,
          errors: ac.errors,
          cleanup: ac.cleanup,
          limits: '仅 HTTP；页面由本轮浏览器单独验证。保留审计，只取消本次未结束任务、停用本次员工。'
        },
        null,
        2
      )
    )
    console.log(ac.output)
    if (
      ac.errors.length ||
      ac.results.some(result => result.status !== 'passed') ||
      ac.cleanup.some(item => item.status === 'failed')
    )
      process.exitCode = 1
  }
}
