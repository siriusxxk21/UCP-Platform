// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, type App } from 'vue'
import TaskBusinessForm from '@/views/nocode/task-center/TaskBusinessForm.vue'
import { nocodePlatformKey, useNocodePlatform, type NocodePlatform } from './platform'
import type { TaskFormContext } from '@/types/nocode/task-center'
import type { RuntimeApi } from '@/api/nocode/runtime'
import type { BusinessFileApi } from '@/api/nocode/business-file'
import { taskEntrySessionKey } from './task-entry-context'
import { taskFormAccessKey, type TaskFormAccess } from './task-form-access'
import type { SaveRecord } from '@/types/nocode/runtime'

const fixture = vi.hoisted(() => ({
  form: vi.fn(),
  entry: vi.fn(),
  submit: vi.fn(),
  selection: vi.fn(),
  fill: vi.fn(),
  related: vi.fn(),
  relatedSelection: vi.fn(),
  relatedFill: vi.fn(),
  fieldRules: vi.fn(),
  relatedFieldRules: vi.fn(),
  files: vi.fn(),
  content: vi.fn(),
  temporaryContent: vi.fn(),
  upload: vi.fn(),
  renew: vi.fn(),
  entryFiles: vi.fn(),
  entryFileContent: vi.fn(),
  entryFileTemporaryContent: vi.fn(),
  entryFileUpload: vi.fn(),
  entryFileRenew: vi.fn(),
  formReceipt: vi.fn(),
  createReceipt: vi.fn(),
  detail: vi.fn(),
  created: vi.fn(),
  confirmed: undefined as (() => void) | undefined,
  props: {} as Record<string, unknown>,
  runtime: undefined as RuntimeApi | undefined,
  bizFiles: undefined as BusinessFileApi | undefined,
  taskAccess: undefined as TaskFormAccess | undefined,
  draftSession: undefined as
    { saveDraft: (record: SaveRecord) => Promise<unknown>; loadDraft: () => Promise<unknown> } | undefined
}))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => ({ context: fixture.entry }) }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: ['readOnly', 'record', 'submitText', 'initialRelatedRecords'],
    setup(props, { emit }) {
      fixture.props = props
      fixture.runtime = useNocodePlatform().runtime
      fixture.bizFiles = useNocodePlatform().bizFiles
      fixture.taskAccess = inject(taskFormAccessKey, undefined)
      fixture.draftSession = inject(taskEntrySessionKey, undefined)
      fixture.confirmed = () => emit('saved')
      return () => h('div', props.submitText)
    }
  })
}))
let app: App, host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(outcome: string, creating = false, extra: Record<string, unknown> = {}) {
  const context = {
    binding: { resource: { applicationId: 'app', applicationVersion: 1, resourceId: 'form' } },
    model: { object: { objectId: 'object' }, permissions: { writeFields: ['name'] }, writeFields: ['name'] },
    form: {},
    record: { record: { id: null, values: { name: '原申请输入' } }, details: {} },
    writableFieldIds: ['name'],
    handling: { outcome, result: null, request: { error: '请补齐说明' } }
  } as unknown as TaskFormContext
  if (extra.initialBusiness) context.record = null
  fixture.form.mockResolvedValue(context)
  app = createApp(TaskBusinessForm, {
    task: creating
      ? undefined
      : ({
          id: 'task',
          revision: 3,
          status: 'RUNNING',
          canExecute: true,
          binding: { applicationId: 'app', formId: 'form', entryId: 'entry' }
        } as never),
    binding: creating ? { applicationId: 'app', formId: 'form', entryId: null } : undefined,
    onCreated: fixture.created,
    ...extra
  })
  app.provide(nocodePlatformKey, {
    runtime: {},
    bizFiles: {
      files: fixture.files,
      content: fixture.content,
      temporaryContent: fixture.temporaryContent,
      upload: fixture.upload,
      renew: fixture.renew
    },
    taskCenter: {
      form: fixture.form,
      formPreview: fixture.form,
      formReceipt: fixture.formReceipt,
      createReceipt: fixture.createReceipt,
      detail: fixture.detail,
      saveBusiness: fixture.submit,
      formSelection: fixture.selection,
      formFill: fixture.fill,
      relatedForm: fixture.related,
      relatedSelection: fixture.relatedSelection,
      relatedFill: fixture.relatedFill,
      formFieldRules: fixture.fieldRules,
      relatedFieldRules: fixture.relatedFieldRules,
      entryFiles: fixture.entryFiles,
      entryFileContent: fixture.entryFileContent,
      entryFileTemporaryContent: fixture.entryFileTemporaryContent,
      entryFileUpload: fixture.entryFileUpload,
      entryFileRenew: fixture.entryFileRenew
    }
  } as unknown as NocodePlatform)
  app.component('ASpin', { render: () => null })
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('p', [props.message, props.description])
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return context
}
beforeEach(() => {
  vi.clearAllMocks()
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('任务固定版本业务办理与重新提交', () => {
  it('业务填写草稿复用编辑器暂存，保存到整份任务且完整恢复主表/明细/关联，不提交业务记录', async () => {
    const record: SaveRecord = {
      applicationId: 'app',
      objectId: 'object',
      id: null,
      expectedRevision: null,
      values: { name: '未填完的业务内容' },
      details: { items: [{ id: null, revision: null, values: { quantity: 2 } }] },
      relatedRecords: { logs: [{ id: null, expectedRevision: null, values: { note: '关联草稿' } }] }
    }
    const saveDraft = vi.fn(async () => {}),
      create = vi.fn(),
      publish = vi.fn()
    await mount('EFFECTIVE', true, { initialBusiness: record, draftKey: 'whole-draft', saveDraft, create, publish })
    expect(fixture.props.record).toEqual({
      record: { id: null, revision: null, values: record.values },
      details: record.details,
      relations: {}
    })
    expect(fixture.props.initialRelatedRecords).toEqual(record.relatedRecords)
    expect(fixture.props.submitText).toBe('加入任务池')
    await fixture.draftSession!.saveDraft(record)
    expect(saveDraft).toHaveBeenCalledExactlyOnceWith(record)
    expect(await fixture.draftSession!.loadDraft()).toMatchObject({
      values: record.values,
      details: record.details,
      relatedRecords: record.relatedRecords
    })
    expect(publish).not.toHaveBeenCalled()
    expect(create).not.toHaveBeenCalled()
    expect(fixture.submit).not.toHaveBeenCalled()
  })
  it('驳回恢复原输入并可重新提交，审批中和生效失败均只读', async () => {
    await mount('REJECTED')
    expect(fixture.props.readOnly).toBe(false)
    expect(fixture.props.submitText).toBe('重新提交')
    expect(fixture.props.record).toMatchObject({ record: { values: { name: '原申请输入' } } })
    expect(host.textContent).toContain('请补齐说明')
    app.unmount()
    host.remove()
    await mount('SUBMITTED')
    expect(fixture.props.readOnly).toBe(true)
    app.unmount()
    host.remove()
    await mount('APPLY_FAILED')
    expect(fixture.props.readOnly).toBe(true)
  })
  it('固定实例不读取公开旧版本入口，所有辅助查询经任务权限重新解析', async () => {
    await mount('CANCELED')
    expect(fixture.entry).not.toHaveBeenCalled()
    const selection = { applicationId: 'app', objectId: 'object', fieldId: 'name' } as never
    const related = { applicationId: 'app' } as never
    await fixture.runtime!.selection(selection)
    await fixture.runtime!.formFill(selection)
    await fixture.runtime!.relatedForm(related)
    await fixture.runtime!.relatedSelection(related, selection)
    await fixture.runtime!.relatedFill(related, selection)
    await fixture.runtime!.evaluateFieldRules(selection)
    await fixture.taskAccess!.relatedFieldRules(related, selection)
    expect(fixture.selection).toHaveBeenCalledWith('task', selection)
    expect(fixture.fill).toHaveBeenCalledWith('task', selection)
    expect(fixture.related).toHaveBeenCalledWith('task', related)
    expect(fixture.relatedSelection).toHaveBeenCalledWith('task', related, selection)
    expect(fixture.relatedFill).toHaveBeenCalledWith('task', related, selection)
    expect(fixture.fieldRules).toHaveBeenCalledWith('task', selection)
    expect(fixture.relatedFieldRules).toHaveBeenCalledWith('task', related, selection)
    expect(fixture.taskAccess!.scope()).toBe('task:task:__business')
  })
  it('无办理项的历史任务附件保留普通应用授权，不伪造 __business 入口或请求任务附件接口', async () => {
    await mount('EFFECTIVE')
    const api = fixture.bizFiles
    if (!api) throw new Error('缺少历史任务附件接口')
    const file = new File(['旧任务附件'], '材料.txt', { type: 'text/plain' })
    const listQuery = {
      applicationId: 'app',
      objectId: 'object',
      recordId: 'record',
      fieldId: 'attachment',
      pageNo: 1,
      pageSize: 20
    }
    const contentQuery = { ...listQuery, entryId: 'file-entry' }
    const temporaryQuery = { objectId: 'object', fieldId: 'attachment', sessionKey: 'session', fileId: 'file' }
    const uploadQuery = { applicationId: 'app', ...temporaryQuery }
    await api.files(listQuery)
    await api.content(contentQuery)
    await api.temporaryContent(temporaryQuery)
    await api.upload(uploadQuery, file)
    await api.renew('session')
    expect(fixture.files).toHaveBeenCalledExactlyOnceWith(listQuery)
    expect(fixture.content).toHaveBeenCalledExactlyOnceWith(contentQuery)
    expect(fixture.temporaryContent).toHaveBeenCalledExactlyOnceWith(temporaryQuery)
    expect(fixture.upload).toHaveBeenCalledExactlyOnceWith(uploadQuery, file)
    expect(fixture.renew).toHaveBeenCalledExactlyOnceWith('session')
    fixture.upload.mockRejectedValueOnce(new Error('没有应用附件写入权限'))
    await expect(api.upload(uploadQuery, file)).rejects.toThrow('没有应用附件写入权限')
    for (const taskFiles of [
      fixture.entryFiles,
      fixture.entryFileContent,
      fixture.entryFileTemporaryContent,
      fixture.entryFileUpload,
      fixture.entryFileRenew
    ])
      expect(taskFiles).not.toHaveBeenCalled()
    expect(fixture.entry).not.toHaveBeenCalled()
    expect(fixture.taskAccess).toBeDefined()
  })
  it('刷新后用服务端持久回执确认原请求，不重提业务数据', async () => {
    const confirmed = { outcome: 'EFFECTIVE', result: { record: { id: 'saved' }, details: {} }, request: null }
    fixture.formReceipt.mockResolvedValue(confirmed)
    await mount('EFFECTIVE')
    expect(await fixture.runtime!.submitReceipt('app', 'object', 'original-request')).toEqual(confirmed)
    expect(fixture.formReceipt).toHaveBeenCalledWith('task', 'original-request')
    expect(fixture.submit).not.toHaveBeenCalled()
  })
  it('发起回包丢失后按业务请求找到真实任务并恢复创建结果', async () => {
    const confirmed = { outcome: 'SUBMITTED', result: null, request: { id: 'request' } },
      created = { task: { id: 'created-task' }, nodes: [], comments: [], events: [] }
    fixture.createReceipt.mockResolvedValue({ taskId: 'created-task', handling: confirmed })
    fixture.detail.mockResolvedValue(created)
    await mount('SUBMITTED', true)
    expect(await fixture.runtime!.submitReceipt('app', 'object', 'business-request')).toEqual(confirmed)
    expect(fixture.createReceipt).toHaveBeenCalledWith('business-request')
    expect(fixture.form).toHaveBeenLastCalledWith('created-task')
    fixture.confirmed!()
    expect(fixture.created).toHaveBeenCalledWith(created)
    expect(fixture.submit).not.toHaveBeenCalled()
  })
})
