import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, provide, ref } from 'vue'
import { mount, find, event, text, flush } from './renderer'
import { workEntryKey } from '@/nocode/work-context'
import { FieldType } from '@/types/nocode/enums'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
const api = vi.hoisted(() => ({
  context: vi.fn(),
  saveDraft: vi.fn(),
  submit: vi.fn(),
  selection: vi.fn(),
  validate: vi.fn(),
  open: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol.for('test-work-platform'),
  useNocodePlatform: () => ({ work: api, runtime: {} })
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn(), info: vi.fn() },
  Modal: { confirm: (p: any) => p.onOk() }
}))
vi.mock('@/views/nocode/application/components/RecordForm.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    setup(_, { attrs, expose }) {
      expose({ validate: api.validate })
      return () => h('record-form', attrs)
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    setup(_, { attrs }) {
      return () => h('read-view', attrs)
    }
  })
}))
import WorkDraftPanel from '@/views/nocode/application/components/WorkDraftPanel.vue'
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'

const mounted: ReturnType<typeof mount>[] = []
function context() {
  const resource = {
    applicationId: '1',
    applicationVersion: 3,
    applicationChecksum: 'fixed',
    resourceId: 'form',
    resourceKind: 'FORM'
  }
  return {
    draft: {
      id: 'draft-1',
      revision: 7,
      state: 'DRAFT',
      resource,
      objectId: '10',
      recordId: null as string | null,
      baseRecordRevision: null as string | null,
      values: { name: '未完成', count: '0', flag: false },
      updatedAt: 1788940000000
    },
    submission: null as any,
    currentRecord: null as any,
    writable: true,
    blockedReason: null,
    recordChanged: false,
    formName: '公司表单',
    form: {
      objectId: '10',
      detailIds: [],
      nodes: ['name', 'count', 'flag'].map(fieldId => uiNode(NodeKind.FIELD, { fieldId }))
    },
    model: {
      object: {
        objectId: '10',
        objectName: '公司',
        fields: [
          { id: 'name', code: 'c_name', name: '名称', type: FieldType.TEXT, required: true },
          { id: 'count', code: 'c_count', name: '数量', type: FieldType.INTEGER },
          { id: 'flag', code: 'c_flag', name: '标记', type: FieldType.BOOLEAN }
        ],
        fieldOptions: {},
        details: [],
        relations: []
      },
      permissions: {
        actions: ['READ', 'CREATE', 'UPDATE'],
        readFields: ['name', 'count', 'flag'],
        writeFields: ['name', 'count', 'flag'],
        readDetails: [],
        writeDetails: []
      },
      writable: true,
      generatedKey: true,
      keyFieldId: null,
      keyType: 'bigint',
      details: {}
    }
  }
}
const button = (page: ReturnType<typeof mount>, label: string) =>
  find(page.root, 'a-button', n => text(n).trim() === label)
async function panel() {
  const page = mount(WorkDraftPanel, { id: 'draft-1' })
  mounted.push(page)
  await flush()
  return page
}
beforeEach(() => {
  vi.resetAllMocks()
  api.validate.mockResolvedValue(undefined)
  api.context.mockImplementation(async () => context())
  api.saveDraft.mockImplementation(async input => ({
    ...context().draft,
    ...input,
    id: input.id || 'draft-1',
    revision: input.expectedRevision + 1
  }))
})
afterEach(() => mounted.splice(0).forEach(m => m.unmount()))

describe('工作草稿的真实 Vue 事件', () => {
  it('工作台侧栏收到实际加载与暂存后的上下文，不单独拼装草稿状态', async () => {
    const received = vi.fn()
    const page = mount(WorkDraftPanel, { id: 'draft-1', task: true, workbench: true, onContext: received })
    mounted.push(page)
    await flush()
    expect(received).toHaveBeenLastCalledWith(
      expect.objectContaining({ draft: expect.objectContaining({ revision: 7 }) })
    )
    await event(find(page.root, 'record-form'), 'onUpdate:modelValue', { name: '最新填写' })
    await event(button(page, '暂存草稿'), 'onClick')
    expect(received).toHaveBeenLastCalledWith(
      expect.objectContaining({ draft: expect.objectContaining({ revision: 8, values: { name: '最新填写' } }) })
    )
  })
  it('工作台完成任务后同步真实材料上下文，并收起写入动作', async () => {
    const received = vi.fn()
    const page = mount(WorkDraftPanel, { id: 'draft-1', task: true, workbench: true, onContext: received })
    mounted.push(page)
    await flush()
    const done = context()
    done.writable = false
    done.submission = {
      id: 'material-1',
      values: { name: '已提交内容' },
      recordId: 'record',
      submittedAt: 1788940000000
    }
    api.context.mockResolvedValueOnce(done)
    api.submit.mockResolvedValueOnce(done.submission)
    await event(button(page, '提交并完成任务'), 'onClick')
    expect(received).toHaveBeenLastCalledWith(
      expect.objectContaining({ submission: expect.objectContaining({ id: 'material-1' }), writable: false })
    )
    expect(text(page.root)).not.toContain('提交并完成任务')
    expect(find(page.root, 'read-view').props.values).toEqual({ name: '已提交内容' })
  })
  it('未完成填写可以暂存，保留 false、0 及固定发布引用，不调用必填校验', async () => {
    const page = await panel()
    await event(find(page.root, 'record-form'), 'onUpdate:modelValue', { count: '0', flag: false })
    await event(button(page, '暂存草稿'), 'onClick')
    expect(api.validate).not.toHaveBeenCalled()
    expect(api.saveDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        expectedRevision: 7,
        resource: context().draft.resource,
        values: { count: '0', flag: false }
      })
    )
    expect(api.submit).not.toHaveBeenCalled()
  })
  it('正式提交先校验，必填失败不会创建提交命令', async () => {
    const page = await panel()
    api.validate.mockRejectedValueOnce(new Error('名称必填'))
    await event(button(page, '正式提交'), 'onClick')
    expect(find(page.root, 'a-alert').props.message).toContain('名称必填')
    expect(api.submit).not.toHaveBeenCalled()
    expect(api.saveDraft).not.toHaveBeenCalled()
  })
  it('脏稿先暂存，网络失败后禁止改稿并使用完全相同的命令重试', async () => {
    const page = await panel()
    await event(find(page.root, 'record-form'), 'onUpdate:modelValue', { name: '提交值' })
    api.submit.mockRejectedValueOnce(new Error('network disconnected'))
    await event(button(page, '正式提交'), 'onClick')
    const first = { ...api.submit.mock.calls[0]![0] }
    expect(first.expectedRevision).toBe(8)
    expect(button(page, '暂存草稿').props.disabled).toBe(true)
    expect(find(page.root, 'div', n => n.props.inert === true)).toBeTruthy()
    api.submit.mockResolvedValueOnce({ id: 'submission' })
    const done = context()
    done.submission = { values: { name: '提交值' }, recordId: 'record', submittedAt: 1788940000000 }
    done.writable = false
    api.context.mockResolvedValueOnce(done)
    await event(button(page, '重试本次提交'), 'onClick')
    expect(api.submit.mock.calls[1]![0]).toEqual(first)
    expect(api.saveDraft).toHaveBeenCalledTimes(1)
    expect(find(page.root, 'read-view').props.values).toEqual({ name: '提交值' })
  })
  it('提交材料仅显示冻结值，不混入当前业务记录的值', async () => {
    const c = context()
    c.submission = { values: { name: '原材料' }, recordId: 'record', submittedAt: 1788940000000 }
    c.writable = false
    c.currentRecord = { id: 'record', revision: '2', values: { name: '后续修改', count: '99' } }
    api.context.mockResolvedValueOnce(c)
    const page = await panel()
    expect(find(page.root, 'read-view').props.values).toEqual({ name: '原材料' })
    expect(text(page.root)).not.toContain('正式提交')
  })
  it('冲突需明确采用最新版本后才能写入', async () => {
    const c = context()
    c.draft.recordId = 'record'
    c.draft.baseRecordRevision = 'old'
    c.currentRecord = { id: 'record', revision: 'new', values: { name: '最新业务值' } }
    c.recordChanged = true
    api.context.mockResolvedValueOnce(c)
    const page = await panel()
    expect(button(page, '暂存草稿').props.disabled).toBe(true)
    await event(button(page, '采用最新业务值'), 'onClick')
    expect(button(page, '暂存草稿').props.disabled).toBe(false)
    await event(button(page, '暂存草稿'), 'onClick')
    expect(api.saveDraft.mock.calls[0]![0]).toMatchObject({ baseRecordRevision: 'new', values: { name: '最新业务值' } })
  })
  it('切换草稿时丢弃过期读取响应', async () => {
    let finish!: (c: any) => void
    api.context.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve
        })
    )
    const id = ref('draft-1'),
      page = mount(defineComponent({ setup: () => () => h(WorkDraftPanel, { id: id.value }) }))
    mounted.push(page)
    await flush()
    const newer = context()
    newer.draft.id = 'draft-2'
    newer.draft.values.name = '第二份'
    api.context.mockResolvedValueOnce(newer)
    id.value = 'draft-2'
    await flush()
    finish(context())
    await flush()
    expect(find(page.root, 'record-form').props.modelValue.name).toBe('第二份')
  })
  it('原业务编辑器暂存时沿用当前展示版本，并进入已保存草稿', async () => {
    const c = context()
    const page = mount(
      defineComponent({
        setup() {
          provide(workEntryKey, {
            application: ref({ application: { id: '1' }, versionNo: 3, checksum: 'fixed' } as any),
            openDraft: api.open
          })
          return () => h(RecordEditor, { applicationId: '1', model: c.model as any, form: c.form, formId: 'form' })
        }
      })
    )
    mounted.push(page)
    await flush()
    await event(find(page.root, 'record-form'), 'onUpdate:modelValue', { count: '0', flag: false })
    await event(button(page, '暂存草稿'), 'onClick')
    expect(api.saveDraft.mock.calls[0]![0]).toMatchObject({
      id: null,
      expectedRevision: null,
      resource: c.draft.resource,
      values: { count: '0', flag: false }
    })
    expect(api.validate).not.toHaveBeenCalled()
    expect(api.open).toHaveBeenCalledWith('draft-1')
  })
})
