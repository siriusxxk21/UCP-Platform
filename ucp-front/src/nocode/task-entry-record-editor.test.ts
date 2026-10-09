// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, type App } from 'vue'
import TaskEntryRecordEditor from '@/views/nocode/task-center/TaskEntryRecordEditor.vue'
import { nocodePlatformKey, useNocodePlatform, type NocodePlatform } from './platform'
import { taskFormAccessKey, type TaskFormAccess } from './task-form-access'
import { createRuntimeApi } from '@/api/nocode/runtime'
import { createBusinessFileApi } from '@/api/nocode/business-file'
import type { NocodeHttpClient } from '@/api/nocode/object'

const fixture = vi.hoisted(() => ({
  platform: undefined as NocodePlatform | undefined,
  access: undefined as TaskFormAccess | undefined,
  folder: vi.fn()
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'employee' } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), RouterLink: { render: () => null } }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/nocode/task-confirmation', () => ({ useTaskConfirmation: () => ({ confirmDiscard: async () => true }) }))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({
  default: defineComponent({
    setup() {
      fixture.platform = useNocodePlatform()
      fixture.access = inject(taskFormAccessKey, undefined)
      return () => h('div', { 'data-task-record': '' })
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordFolderPanel.vue', () => ({
  default: defineComponent({
    setup() {
      fixture.folder()
      return () => h('div', '普通记录文件夹')
    }
  })
}))
let app: App, host: HTMLElement
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.clearAllMocks()
})
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
describe('任务办理表单实际组件隔离', () => {
  it('只读任务记录复用编辑器但不加载普通文件夹，主表与关联规则仍带任务上下文', async () => {
    const target = { taskId: 'task', entryKey: 'wifi', recordId: 'record', contributionId: null }
    const permissions = {
      actions: ['READ'],
      readFields: [],
      writeFields: [],
      readDetails: [],
      writeDetails: [],
      readRelations: [],
      writeRelations: []
    }
    const form = {
      binding: { resource: { applicationId: 'app', resourceId: 'form' } },
      model: {
        writable: false,
        permissions,
        details: {},
        object: { objectId: 'object', fields: [], fieldOptions: {}, details: [], relations: [], settings: {} }
      },
      record: { record: { id: 'record', revision: '1', values: {}, permissions }, details: {} },
      form: { fieldIds: [], detailIds: [], nodes: [] }
    }
    const entryForm = vi.fn().mockResolvedValue(form),
      entryFieldRules = vi.fn().mockResolvedValue({ results: [] }),
      entryRelatedFieldRules = vi.fn().mockResolvedValue({ results: [] })
    const get = vi.fn(),
      post = vi.fn(),
      client = { get, post } as unknown as NocodeHttpClient
    app = createApp(TaskEntryRecordEditor, { target, readonly: true })
    app.provide(nocodePlatformKey, {
      runtime: createRuntimeApi(client),
      bizFiles: createBusinessFileApi(client),
      taskCenter: { entryForm, entryFieldRules, entryRelatedFieldRules }
    } as unknown as NocodePlatform)
    const plain = defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('div', slots.default?.())
    })
    for (const name of [
      'ASpin',
      'AAlert',
      'AButton',
      'ASpace',
      'ATag',
      'AInputSearch',
      'AInput',
      'ARadioGroup',
      'ATooltip',
      'AEmpty',
      'AList',
      'AListItem',
      'ASelect',
      'AForm',
      'AFormItem',
      'ATextarea'
    ])
      app.component(name, plain)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
    expect(host.querySelector('[data-task-record]')).not.toBeNull()
    expect(fixture.folder).not.toHaveBeenCalled()
    expect(fixture.access!.scope()).toBe('task:task:wifi')
    const query = { applicationId: 'app', objectId: 'object', formId: 'form', values: {} }
    await fixture.platform!.runtime.evaluateFieldRules(query)
    const source = { applicationId: 'app', objectId: 'object', formId: 'form', bindingId: 'child' }
    await fixture.access!.relatedFieldRules(source, { ...query, objectId: 'child-object' })
    expect(entryFieldRules).toHaveBeenCalledWith(target, query)
    expect(entryRelatedFieldRules).toHaveBeenCalledWith(target, source, { ...query, objectId: 'child-object' })
    await expect(fixture.platform!.runtime.get('app', 'object', 'record')).rejects.toThrow('未授权')
    expect(get).not.toHaveBeenCalled()
    expect(post).not.toHaveBeenCalled()
  })
})
