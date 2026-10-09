// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, provide, type App, type Component } from 'vue'
import { message } from 'ant-design-vue'
import TaskClaimDialog from '@/views/nocode/task-center/TaskClaimDialog.vue'
import TaskAssignmentDialog from '@/views/nocode/task-center/TaskAssignmentDialog.vue'
import { newTaskNode } from './task-center'
import type { TaskMember, TaskRow } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  claim: vi.fn(),
  claimPreview: vi.fn(),
  claimGroup: vi.fn(),
  assign: vi.fn(),
  members: vi.fn(),
  checklistContext: vi.fn(),
  checklist: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'okText', 'cancelText', 'loading'],
    emits: ['ok', 'cancel'],
    setup:
      (p, { slots, emit }) =>
      () =>
        p.open
          ? h('section', [
              h('h2', p.title),
              slots.formItems?.(),
              slots.footer?.() || [
                h('button', { disabled: p.loading, onClick: () => emit('ok') }, p.okText || '确定'),
                h('button', { disabled: p.loading, onClick: () => emit('cancel') }, p.cancelText || '取消')
              ]
            ])
          : null
  })
}))
vi.mock('@/views/nocode/task-center/TaskAssignmentFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'members'],
    emits: ['update:modelValue'],
    setup:
      (p, { emit }) =>
      () =>
        h('div', [
          h(
            'button',
            {
              onClick: () =>
                emit('update:modelValue', { ...p.modelValue, assignmentMode: 'UNASSIGNED', assigneeId: null })
            },
            '改为暂不分配'
          ),
          ...((p.members || []) as TaskMember[]).map(member =>
            h(
              'button',
              {
                onClick: () =>
                  emit('update:modelValue', { ...p.modelValue, assignmentMode: 'ASSIGNED', assigneeId: member.id })
              },
              `指定：${member.name}`
            )
          )
        ])
  })
}))
const row = (): TaskRow => ({
  ...newTaskNode(),
  id: 'task',
  title: '现场工作',
  rootId: 'root',
  parentId: 'root',
  assignmentMode: 'ASSIGNED',
  assigneeId: '2105987704820396033',
  assigneeName: '员工',
  creatorId: '1',
  creatorName: '负责人',
  status: 'PENDING',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '',
  revision: 3,
  instanceRevision: 3,
  childCount: 0,
  plans: [],
  canStart: true,
  canExecute: true,
  canEdit: true,
  canClaim: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) => {
  const result = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
    node => node.textContent?.trim() === label
  )
  if (!result) throw new Error(`缺少按钮：${label}，当前内容：${host.textContent}`)
  return result
}
async function mount(component: Component, props: Record<string, unknown> = {}) {
  app = createApp(() => h(component, { task: row(), ...props }))
  const plain = defineComponent({
    props: ['label', 'message'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.label, p.message, slots.default?.()])
  })
  for (const name of ['AFormItem', 'AAlert', 'ASpace', 'ASpin', 'ACheckbox']) app.component(name, plain)
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('textarea', {
            value: p.value,
            disabled: p.disabled,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value)
          })
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled || p.loading }, slots.default?.())
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'disabled', 'options'],
      emits: ['update:value', 'select'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value,
              disabled: p.disabled,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('select', value)
              }
            },
            (p.options || []).map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'ADatePicker',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      emits: ['update:value'],
      setup: (_, { emit, slots }) => {
        provide('choose', (value: unknown) => emit('update:value', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup: (p, { slots }) => {
        const choose = inject<(value: unknown) => void>('choose')
        return () => h('button', { onClick: () => choose?.(p.value) }, slots.default?.())
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.claim.mockResolvedValue({ task: row() })
  api.claimGroup.mockResolvedValue({ task: { ...row(), id: 'root' } })
  api.claimPreview.mockResolvedValue({
    rootId: 'root',
    title: '整件施工',
    instanceRevision: 7,
    items: [
      { ...row(), id: 'root', title: '整件施工' },
      { ...row(), id: 'follow', title: '随总任务的准备', assignmentMode: 'FOLLOW_ROOT' }
    ]
  })
  api.assign.mockResolvedValue({ task: row() })
  api.members.mockResolvedValue([])
  api.checklistContext.mockResolvedValue({
    today: '2030-10-04',
    weekStart: '2030-09-30',
    weekEnd: '2030-10-06',
    items: [
      {
        taskId: 'task',
        title: '现场工作',
        assigneeId: row().assigneeId,
        status: 'PENDING',
        version: 8,
        todayPlans: [],
        weekPlans: [],
        history: [],
        canAdd: true,
        canCancel: true,
        readOnly: false,
        warnings: []
      }
    ]
  })
  api.checklist.mockResolvedValue({ changed: ['task'], unchanged: [] })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('领取和分配后的计划衔接', () => {
  it('真实总任务明确请求包含开放子项，确认后只调用整项接口且不自动加入子项计划', async () => {
    api.claimPreview.mockResolvedValueOnce({
      rootId: 'root',
      title: '整件施工',
      instanceRevision: 7,
      items: [
        { ...row(), id: 'root', title: '整件施工' },
        { ...row(), id: 'a', title: '现场测量', assignmentMode: 'OPEN' },
        { ...row(), id: 'b', title: '工程收尾', assignmentMode: 'OPEN' }
      ]
    })
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root', parentId: null } })
    expect(api.claimPreview).toHaveBeenCalledWith('root', true)
    expect(api.claimGroup).not.toHaveBeenCalled()
    expect(host.querySelector('h2')?.textContent).toBe('领取整个任务')
    expect(host.textContent).toContain('一起领取的子任务（2）')
    expect(host.textContent).toContain('现场测量')
    expect(host.textContent).toContain('工程收尾')
    expect(host.textContent).toContain('已分配给同事的任务保持不变')
    expect(host.textContent).not.toContain('承接范围')
    expect(host.textContent).not.toContain('重新加载')
    button('稍后加入').click()
    await flush()
    button('仅领取').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledWith(
      expect.objectContaining({ rootId: 'root', expectedInstanceRevision: 7, includeOpen: true })
    )
    expect(api.claim).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('整项领取未知结果保留原预览版本和请求键，禁止重新预览后盲重领', async () => {
    api.claimGroup.mockRejectedValueOnce(new Error('结果未知'))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root' }, planReadonly: true })
    button('仅领取').click()
    await flush()
    expect(host.textContent).toContain('领取结果尚未确认')
    expect(host.textContent).not.toContain('重新加载')
    button('仅领取').click()
    await flush()
    expect(api.claimPreview).toHaveBeenCalledOnce()
    expect(api.claimGroup.mock.calls[0]?.[0]).toMatchObject({ includeOpen: true })
    expect(api.claimGroup.mock.calls[1]?.[0]).toEqual(api.claimGroup.mock.calls[0]?.[0])
  })
  it('预览过期被明确拒绝后必须重新预览，不能默默承接新的范围', async () => {
    api.claimGroup.mockRejectedValueOnce(Object.assign(new Error('任务已变化'), { businessCode: 409 }))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root' }, planReadonly: true })
    button('仅领取').click()
    await flush()
    button('仅领取').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledOnce()
    expect(host.textContent).toContain('任务信息尚未加载完成，请加载后再领取')
    api.claimPreview.mockResolvedValueOnce({ rootId: 'root', title: '整件施工', instanceRevision: 9, items: [] })
    button('重新加载').click()
    await flush()
    button('仅领取').click()
    await flush()
    expect(api.claimGroup.mock.calls[1]?.[0]).toMatchObject({ expectedInstanceRevision: 9 })
    expect(api.claimPreview).toHaveBeenLastCalledWith('root', true)
    expect(api.claimGroup.mock.calls[1]?.[0].requestKey).not.toBe(api.claimGroup.mock.calls[0]?.[0].requestKey)
  })
  it('整项已领取而计划失败，只重试总任务自己的计划不重新领取整组', async () => {
    api.checklistContext.mockResolvedValue({
      today: '2030-10-04',
      weekStart: '2030-09-30',
      items: [{ taskId: 'root', version: 10, canAdd: true }]
    })
    api.checklist.mockRejectedValueOnce(new Error('计划回执未知'))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root' } })
    button('领取并加入计划').click()
    await flush()
    button('重试加入计划').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledOnce()
    expect(api.claim).not.toHaveBeenCalled()
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['root'], target: 'SELF' })
    expect(api.checklist.mock.calls[1]?.[0]).toEqual(api.checklist.mock.calls[0]?.[0])
  })
  it('预览加载失败不得提交领取，重新预览可恢复', async () => {
    api.claimPreview.mockRejectedValueOnce(new Error('预览不可用'))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root' }, planReadonly: true })
    button('仅领取').click()
    await flush()
    expect(api.claimGroup).not.toHaveBeenCalled()
    button('重新加载').click()
    await flush()
    button('仅领取').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledOnce()
  })
  it('只领取总任务时用自然语言说明，不重复列根任务或显示零子项', async () => {
    api.claimPreview.mockResolvedValueOnce({
      rootId: 'root',
      title: '办公室装修',
      instanceRevision: 7,
      items: [{ ...row(), id: 'root', title: '办公室装修' }]
    })
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root', title: '办公室装修' } })
    expect(host.querySelector('h2')?.textContent).toBe('领取任务')
    expect(host.textContent).toContain('本次只领取这个任务，不会自动领取其他任务')
    expect(host.textContent?.match(/办公室装修/g)).toHaveLength(1)
    expect(host.textContent).not.toContain('0 项')
    expect(host.textContent).not.toContain('预览')
    expect(host.textContent).not.toContain('承接')
    expect(api.claimGroup).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('补领明确只领取剩余子任务，不声称重新领取或开始已有的总任务', async () => {
    api.claimPreview.mockResolvedValueOnce({
      rootId: 'root',
      title: '施工总任务',
      instanceRevision: 8,
      remainingOnly: true,
      items: [
        { ...row(), id: 'a', title: '测量' },
        { ...row(), id: 'b', title: '收尾' }
      ]
    })
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root', status: 'RUNNING' }, planReadonly: true })
    expect(host.querySelector('h2')?.textContent).toBe('领取剩余子任务')
    expect(host.textContent).toContain('总任务已由你负责')
    expect(host.textContent).toContain('本次领取的子任务（2）')
    expect(host.textContent).toContain('总任务当前进度保持不变')
    expect(host.textContent).not.toContain('领取后，你将负责')
    button('确认领取剩余子任务').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledWith(
      expect.objectContaining({ rootId: 'root', includeOpen: true, expectedInstanceRevision: 8 })
    )
    expect(api.claim).not.toHaveBeenCalled()
    expect(message.success).toHaveBeenCalledWith('已领取剩余子任务')
  })
  it('补领回执未知时保留原预览和请求键，不能重新请求或退回普通整领', async () => {
    api.claimPreview.mockResolvedValueOnce({
      rootId: 'root',
      title: '施工总任务',
      instanceRevision: 8,
      remainingOnly: true,
      items: [{ ...row(), id: 'a', title: '测量' }]
    })
    api.claimGroup.mockRejectedValueOnce(new Error('结果未知'))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root' }, planReadonly: true })
    button('确认领取剩余子任务').click()
    await flush()
    expect(host.querySelector('h2')?.textContent).toBe('领取剩余子任务')
    expect(host.textContent).not.toContain('重新加载')
    button('确认领取剩余子任务').click()
    await flush()
    expect(api.claimPreview).toHaveBeenCalledOnce()
    expect(api.claimGroup.mock.calls[1]?.[0]).toEqual(api.claimGroup.mock.calls[0]?.[0])
  })
  it('补领后计划仍只作用总任务，计划失败只重试计划且不称总任务尚未开始', async () => {
    api.claimPreview.mockResolvedValueOnce({
      rootId: 'root',
      title: '施工总任务',
      instanceRevision: 8,
      remainingOnly: true,
      items: [{ ...row(), id: 'a', title: '测量' }]
    })
    api.checklistContext.mockResolvedValue({
      today: '2030-10-04',
      weekStart: '2030-09-30',
      items: [{ taskId: 'root', version: 10, canAdd: true }]
    })
    api.checklist.mockRejectedValueOnce(new Error('计划回执未知'))
    await mount(TaskClaimDialog, { task: { ...row(), id: 'root', status: 'RUNNING' } })
    expect(host.textContent).toContain('顺便将总任务加入我的计划')
    expect(host.textContent).toContain('子任务可稍后单独安排')
    button('领取并加入计划').click()
    await flush()
    expect(host.textContent).toContain('子任务已归你负责')
    button('重试加入计划').click()
    await flush()
    expect(api.claimGroup).toHaveBeenCalledOnce()
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['root'], target: 'SELF' })
    expect(api.checklist).toHaveBeenCalledTimes(2)
    expect(api.checklist.mock.calls[1]?.[0]).toEqual(api.checklist.mock.calls[0]?.[0])
    expect(message.success).toHaveBeenCalledWith('已领取剩余子任务，总任务已加入计划')
  })
  it('管理入口领取只变更负责人，不附带个人计划', async () => {
    await mount(TaskClaimDialog, { planReadonly: true })
    expect(host.textContent).not.toContain('加入今日计划')
    expect(host.textContent).not.toContain('加入本周计划')
    expect(host.textContent).toContain('“我的任务”')
    button('仅领取').click()
    await flush()
    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.claimPreview).not.toHaveBeenCalled()
    expect(api.claimGroup).not.toHaveBeenCalled()
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('仅分配负责人默认不替员工加入计划', async () => {
    await mount(TaskAssignmentDialog)
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledOnce()
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('受限协调分工不接受暂不分配且保留安全返回详情', async () => {
    const saved = vi.fn()
    api.assign.mockResolvedValue({ task: { ...row(), id: 'root' } })
    await mount(TaskAssignmentDialog, {
      task: { ...row(), assignmentMode: 'FOLLOW_ROOT', canAssign: false, canDelegate: true },
      onSaved: saved
    })
    expect(host.textContent).toContain('当前负责人：员工')
    button('改为暂不分配').click()
    await flush()
    button('保存人员安排').click()
    await flush()
    expect(api.assign).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请选择指定负责人或开放领取')
    app?.unmount()
    host.remove()
    await mount(TaskAssignmentDialog, {
      task: { ...row(), assignmentMode: 'FOLLOW_ROOT', canAssign: false, canDelegate: true },
      onSaved: saved
    })
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(
      expect.objectContaining({ assignmentMode: 'ASSIGNED', assigneeId: row().assigneeId })
    )
    expect(api.assign.mock.calls[0]?.[0]).not.toHaveProperty('acceptance')
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ task: expect.objectContaining({ id: 'root' }) }))
  })
  it('分出后协调者可以收回自己负责，不附带个人计划', async () => {
    await mount(TaskAssignmentDialog, { currentUserId: 'coordinator', task: { ...row(), canDelegate: true } })
    button('改由我负责').click()
    await flush()
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeId: 'coordinator', assignmentMode: 'ASSIGNED' })
    )
    expect(api.assign.mock.calls[0]?.[0]).not.toHaveProperty('transfer')
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it.each(['RUNNING', 'PAUSED'] as const)('%s 显式转交要求新接收人及交接说明，并保留重试请求', async status => {
    api.members.mockResolvedValue([
      { id: 'new-owner', name: '同事' },
      { id: row().assigneeId, name: '原负责人' }
    ])
    await mount(TaskAssignmentDialog, { task: { ...row(), status, canTransfer: true } })
    if (status === 'PAUSED') expect(host.textContent).toContain('转交后仍为暂停状态')
    button('确认转交').click()
    await flush()
    expect(host.textContent).toContain('请选择负责人')
    button('指定：原负责人').click()
    await flush()
    button('确认转交').click()
    await flush()
    expect(host.textContent).toContain('请选择与当前负责人不同的接收人')
    button('指定：同事').click()
    await flush()
    button('确认转交').click()
    await flush()
    expect(host.textContent).toContain('请填写交接说明')
    expect(api.assign).not.toHaveBeenCalled()
    const note = host.querySelector<HTMLTextAreaElement>('textarea')
    if (!note) throw new Error('缺少交接说明输入框')
    note.value = '  已完成测量，请继续安装  '
    note.dispatchEvent(new Event('input'))
    await flush()
    api.assign.mockRejectedValueOnce(new Error('网络暂不可用'))
    button('确认转交').click()
    await flush()
    expect(note.disabled).toBe(true)
    expect(api.assign).toHaveBeenCalledWith(
      expect.objectContaining({
        transfer: true,
        note: '已完成测量，请继续安装',
        assigneeId: 'new-owner',
        assignmentMode: 'ASSIGNED',
        candidateUserIds: []
      })
    )
    const first = api.assign.mock.calls[0]?.[0]
    expect(first).not.toHaveProperty('acceptance')
    button('确认转交').click()
    await flush()
    expect(api.assign.mock.calls[1]?.[0]).toEqual(first)
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('转交不能变成开放领取或暂不分配', async () => {
    await mount(TaskAssignmentDialog, { task: { ...row(), status: 'RUNNING', canTransfer: true } })
    button('改为暂不分配').click()
    await flush()
    button('确认转交').click()
    await flush()
    expect(host.textContent).toContain('转交任务需要指定新的负责人')
    expect(api.assign).not.toHaveBeenCalled()
  })
  it('分配弹窗不提供今日或本周清单操作', async () => {
    await mount(TaskAssignmentDialog)
    expect(host.textContent).not.toContain('加入今日计划')
    expect(host.textContent).not.toContain('加入本周计划')
    expect(host.textContent).not.toContain('稍后加入')
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledOnce()
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('领取失败留在领取阶段，重试复用原请求键', async () => {
    api.claim.mockRejectedValueOnce(new Error('回执暂不可用'))
    await mount(TaskClaimDialog)
    button('领取并加入计划').click()
    await flush()
    expect(host.textContent).toContain('回执暂不可用')
    expect(api.checklistContext).not.toHaveBeenCalled()
    button('领取并加入计划').click()
    await flush()
    expect(api.claim.mock.calls[1]?.[0]).toEqual(api.claim.mock.calls[0]?.[0])
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['task'], target: 'SELF' })
  })
  it('领取成功后加入失败只重试清单，不再次领取且复用完整请求', async () => {
    api.checklist.mockRejectedValueOnce(new Error('网络超时'))
    const saved = vi.fn(),
      close = vi.fn()
    await mount(TaskClaimDialog, { onSaved: saved, onClose: close })
    button('领取并加入计划').click()
    await flush()
    expect(saved).toHaveBeenCalledOnce()
    expect(host.textContent).toContain('已领取，但加入计划未完成')
    expect(close).not.toHaveBeenCalled()
    button('重试加入计划').click()
    await flush()
    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.checklistContext).toHaveBeenCalledOnce()
    expect(api.checklist.mock.calls[1]?.[0]).toEqual(api.checklist.mock.calls[0]?.[0])
    expect(api.checklist).toHaveBeenLastCalledWith(
      expect.objectContaining({
        action: 'ADD',
        target: 'SELF',
        period: 'WEEK',
        date: '2030-09-30',
        expectedVersions: { task: 8 }
      })
    )
    expect(close).toHaveBeenCalledOnce()
  })
  it('领取已成功但上下文读取失败，可改为稍后加入而不重复领取', async () => {
    api.checklistContext.mockRejectedValueOnce(new Error('暂不可用'))
    const close = vi.fn()
    await mount(TaskClaimDialog, { onClose: close })
    button('领取并加入计划').click()
    await flush()
    expect(host.textContent).toContain('任务已经归你负责')
    button('稍后加入').click()
    await flush()
    button('完成领取，暂不加入').click()
    await flush()
    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.checklist).not.toHaveBeenCalled()
    expect(close).toHaveBeenCalledOnce()
  })
  it('稍后安排只领取，不提前请求或伪造工作安排', async () => {
    const close = vi.fn()
    await mount(TaskClaimDialog, { onClose: close })
    button('稍后加入').click()
    await flush()
    button('仅领取').click()
    await flush()
    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
    expect(close).toHaveBeenCalledOnce()
  })
  it('今日选择与领取同弹窗一次提交，日期采用服务端锚点', async () => {
    await mount(TaskClaimDialog)
    button('加入今日计划').click()
    await flush()
    button('领取并加入计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(expect.objectContaining({ period: 'DAY', date: '2030-10-04' }))
    expect(host.querySelectorAll('section')).toHaveLength(1)
    expect(host.textContent).not.toContain('明天')
    expect(api.checklist.mock.calls[0]?.[0]).not.toHaveProperty('endDate')
  })
  it('分配未参与任务的启用员工，不将评论提醒成员当作负责人候选范围', async () => {
    const initiator = { id: '1', name: '发起人' }
    const newcomer = { id: '2105987704820396041', name: '新员工' }
    api.members.mockImplementation(async taskId => (taskId ? [initiator] : [initiator, newcomer]))
    await mount(TaskAssignmentDialog, { task: { ...row(), assignmentMode: 'UNASSIGNED', assigneeId: null } })
    button('指定：新员工').click()
    await flush()
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(
      expect.objectContaining({ assignmentMode: 'ASSIGNED', assigneeId: newcomer.id })
    )
  })
  it('分配失败仅重试人员安排，保留雪花负责人 ID 和请求键', async () => {
    api.assign.mockRejectedValueOnce(new Error('人员保存失败'))
    await mount(TaskAssignmentDialog)
    button('保存人员安排').click()
    await flush()
    expect(host.textContent).toContain('人员保存失败')
    button('保存人员安排').click()
    await flush()
    expect(api.assign.mock.calls[1]?.[0]).toEqual(api.assign.mock.calls[0]?.[0])
    expect(api.assign).toHaveBeenCalledWith(expect.objectContaining({ assigneeId: '2105987704820396033' }))
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('暂不分配只更新人员安排，不为不存在的负责人安排计划', async () => {
    const close = vi.fn()
    await mount(TaskAssignmentDialog, { onClose: close })
    button('改为暂不分配').click()
    await flush()
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(expect.objectContaining({ assignmentMode: 'UNASSIGNED', assigneeId: null }))
    expect(api.checklistContext).not.toHaveBeenCalled()
    expect(close).toHaveBeenCalledOnce()
  })
  it('历史已分配任务省略人员模式仍能分配，不附带个人计划', async () => {
    await mount(TaskAssignmentDialog, { task: { ...row(), legacyProtocol: true } })
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(expect.objectContaining({ assignmentMode: 'ASSIGNED' }))
    expect(api.checklistContext).not.toHaveBeenCalled()
  })
  it('总任务安排保留验收人，子任务安排不携带验收补丁', async () => {
    await mount(TaskAssignmentDialog, {
      task: { ...row(), id: 'root', parentId: null, acceptorId: '9007199254740993', acceptorName: '验收人' }
    })
    expect(host.textContent).toContain('验收人（可选）')
    expect(host.textContent).toContain('验收人')
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'root', acceptance: { acceptorId: '9007199254740993' } })
    )
    app?.unmount()
    host.remove()
    api.assign.mockClear()
    await mount(TaskAssignmentDialog)
    expect(host.textContent).not.toContain('验收人（可选）')
    button('保存人员安排').click()
    await flush()
    expect(api.assign.mock.calls[0]?.[0]).not.toHaveProperty('acceptance')
  })
  it('取消总任务验收人提交显式 null，不误保留旧配置', async () => {
    await mount(TaskAssignmentDialog, {
      task: { ...row(), id: 'root', parentId: null, acceptorId: '9007199254740993', acceptorName: '验收人' }
    })
    const acceptance = host.querySelector<HTMLSelectElement>('[aria-label="验收人"]')
    if (!acceptance) throw new Error('缺少验收方式选择框')
    expect(acceptance.value).toBe('ASSIGNED')
    acceptance.value = 'NONE'
    acceptance.dispatchEvent(new Event('change'))
    await flush()
    expect(acceptance.value).toBe('NONE')
    expect(host.textContent).toContain('不设置验收人')
    button('保存人员安排').click()
    await flush()
    expect(api.assign).toHaveBeenCalledWith(expect.objectContaining({ acceptance: { acceptorId: null } }))
  })
  it('更换负责人造成与验收人冲突时提交前拦截，不静默清除验收人', async () => {
    api.members.mockResolvedValue([{ id: '9007199254740993', name: '验收人' }])
    await mount(TaskAssignmentDialog, {
      task: { ...row(), id: 'root', parentId: null, acceptorId: '9007199254740993', acceptorName: '验收人' }
    })
    button('指定：验收人').click()
    await flush()
    button('保存人员安排').click()
    await flush()
    expect(host.textContent).toContain('负责人和验收人不能是同一人')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="验收人"]')?.value).toBe('ASSIGNED')
    expect(api.assign).not.toHaveBeenCalled()
  })
})
