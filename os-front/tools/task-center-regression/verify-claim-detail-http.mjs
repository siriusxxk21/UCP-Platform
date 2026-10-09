import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { TaskDataPolicyAcceptance } from './data-policy-acceptance.mjs'

// 默认不写入；验证只创建本轮候选员工和任务，结束后取消任务、停用临时账号，保留审计。
if (process.env.TASK_CLAIM_DETAIL_RUN !== '1') {
  console.log('设置 TASK_CLAIM_DETAIL_RUN=1，验证领取前详情字段、权限和无副作用。')
} else {
  const ac = new TaskDataPolicyAcceptance()
  ac.prefix = `claimdetail${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
  ac.output = resolve('.work/task-claim-detail', ac.prefix)
  try {
    await ac.login()
    await ac.prepareUsers()
    const start = new Date()
    start.setDate(start.getDate() + 1)
    start.setHours(0, 0, 0, 0)
    const end = new Date(start)
    end.setDate(end.getDate() + 2)
    const original = await ac.create(
      ac.node('领取前工作要求', {
        description: '<p>核对装修材料，完成后提交结果。</p>',
        priority: 'HIGH',
        effectiveWorkMinutes: 120,
        acceptorId: ac.adminId,
        candidateUserIds: [ac.employeeA],
        schedule: {
          mode: 'FIXED',
          fixedStart: start.valueOf(),
          fixedEnd: end.valueOf(),
          offsetDays: 0,
          durationDays: 0
        }
      })
    )
    const id = original.task.id
    await ac.check('候选员工可预览完整工作要求，但无执行和业务权限', async () => {
      const detail = await ac.taskApi('detail', { id }, ac.tokens[ac.employeeA])
      assert.equal(detail.preview.description, original.task.description)
      assert.equal(detail.preview.priority, 'HIGH')
      assert.equal(detail.preview.expectedStart, original.task.expectedStart)
      assert.equal(detail.preview.expectedEnd, original.task.expectedEnd)
      assert.equal(detail.preview.effectiveWorkMinutes, 120)
      assert.equal(String(detail.preview.acceptorId), ac.adminId)
      assert.ok(detail.preview.acceptorName)
      assert.ok(detail.preview.createdAt)
      assert.equal(detail.task.schedule, null)
      for (const flag of ['canStart', 'canExecute', 'canEdit', 'canAssign', 'canAccept', 'canPause', 'canResume'])
        assert.ok(!detail.task[flag], flag)
      assert.deepEqual(detail.comments, [])
      assert.deepEqual(detail.events, [])
      assert.deepEqual(detail.links, [])
      return { id, readOnly: true }
    })
    await ac.check('受限员工不能借详情入口查看任务', () =>
      ac.denied('/nocode/tasks/detail', { id }, ac.tokens[ac.employeeB])
    )
    await ac.check('预览不领取、不启动、不产生操作事件，原完整详情保持不变', async () => {
      const current = await ac.taskApi('detail', { id })
      assert.equal(current.preview, undefined)
      assert.equal(current.task.revision, original.task.revision)
      assert.equal(current.task.assigneeId, null)
      assert.equal(current.task.actualStart, null)
      assert.equal(current.events.length, original.events.length)
      return { revision: current.task.revision, status: current.task.status }
    })
    if (ac.results.some(result => result.status !== 'passed')) process.exitCode = 1
  } finally {
    await ac.finish()
    if (ac.cleanup.some(item => item.status === 'failed') || ac.errors.length) process.exitCode = 1
    await writeFile(
      resolve(ac.output, 'result.json'),
      JSON.stringify({ results: ac.results, cleanup: ac.cleanup, errors: ac.errors }, null, 2)
    )
    console.log(`领取前详情验证记录：${ac.output}`)
  }
}
