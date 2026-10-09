// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import TaskEntryManager from '@/views/nocode/application/components/TaskEntryManager.vue'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { TaskEntryMode, type TaskEntryConfig } from '@/types/nocode/task-entry'
import type { ObjectGrant } from '@/types/nocode/authorization'

vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: vi.fn() }, message: { success: vi.fn(), info: vi.fn() } }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ hasPermission: () => false }) }))
vi.mock('@/views/nocode/application/components/ApplicationMembers.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
// 授权表单用记录属性的替身：这里只验证入口把什么交给它。
vi.mock('@/views/nocode/components/ObjectGrantFields.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: { modelValue: Object, ceiling: Object, writeAllAllowed: { type: Boolean, default: true } },
      setup: p => () =>
        h('i', {
          'data-grant': (p.modelValue as { objectId: string }).objectId,
          'data-write-all': String(p.writeAllAllowed)
        })
    })
  }
})

const object = (objectId: string, objectName: string, relations: unknown[] = []) => ({
  objectId,
  versionNo: 1,
  checksum: 'v1',
  definition: {
    objectId,
    objectName,
    fields: [
      { id: 'f1', name: '字段一' },
      { id: 'f2', name: '字段二' }
    ],
    fieldOptions: {},
    details: [{ id: 'd1', name: '明细', state: 'ACTIVE' }],
    relations
  }
})
const objects = {
  orders: object('orders', '订单', [
    { id: 'r-customer', name: '客户', kind: 'REFERENCE', targetObjectId: 'customers', sourceDetailId: null },
    { id: 'r-contract', name: '合同', kind: 'ONE_TO_ONE', targetObjectId: 'contracts', sourceDetailId: null }
  ]),
  customers: object('customers', '客户'),
  contracts: object('contracts', '合同')
} as unknown as Record<string, PublishedObject>
const form: ApplicationResource = {
  id: 'form',
  kind: ResourceKind.FORM,
  code: 'order_form',
  name: '订单表单',
  config: {
    objectId: 'orders',
    nodes: [],
    relatedForms: [
      {
        id: 'b1',
        sourceObjectId: 'orders',
        relationId: 'r-contract',
        direction: 'OUTGOING',
        formId: 'x',
        title: '合同'
      }
    ]
  }
}
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
interface Setup {
  open: (resource?: ApplicationResource) => void
  config: TaskEntryConfig | undefined
  chooseObject: () => void
  changeMode: () => void
  chooseDependencies: (ids: string[]) => void
  syncFormObjects: () => void
}
async function mount(resources: ApplicationResource[] = [form]) {
  const state = reactive({ resources })
  app = createApp(() =>
    h(TaskEntryManager, {
      applicationId: 'application',
      objects,
      modelValue: state.resources,
      'onUpdate:modelValue': (value: ApplicationResource[]) => {
        state.resources = value
      }
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ARow',
    'ACol',
    'ASelect',
    'ARadioGroup',
    'ARadioButton',
    'AAutoComplete',
    'ACollapse',
    'ACollapsePanel',
    'AInputNumber',
    'AInput',
    'AAlert',
    'AButton',
    'APopconfirm',
    'ASpace'
  ])
    app.component(name, plain)
  app.component(
    'AModal',
    defineComponent({
      props: ['open'],
      setup:
        (p, { slots }) =>
        () =>
          p.open ? h('section', slots.default?.()) : null
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  // 根组件是渲染函数，TaskEntryManager 是它唯一的子组件。
  const root = (app as unknown as { _instance: { subTree: { component: { setupState: Setup } } } })._instance
  return root.subTree.component.setupState
}
const limits = (setup: Setup): ObjectGrant[] => JSON.parse(JSON.stringify(setup.config?.limits))
const writeAll = () =>
  Object.fromEntries(
    Array.from(host.querySelectorAll('[data-grant]')).map(el => [
      el.getAttribute('data-grant'),
      el.getAttribute('data-write-all')
    ])
  )
beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('任务入口允许范围的默认值', () => {
  it('入口主对象（列表办理）：查看 + 新增 + 修改、本人创建，六个清单都是全部', async () => {
    const setup = await mount()
    setup.open()
    await flush()
    if (!setup.config) throw new Error('入口配置未打开')
    setup.config.objectId = 'orders'
    setup.chooseObject()
    await flush()
    expect(limits(setup)).toEqual([
      {
        objectId: 'orders',
        actions: ['READ', 'CREATE', 'UPDATE'],
        scope: 'OWN',
        readFields: ['*'],
        writeFields: ['*'],
        readDetails: ['*'],
        writeDetails: ['*'],
        readRelations: ['*'],
        writeRelations: ['*'],
        actionScopes: {}
      }
    ])
  })
  it('入口主对象（直接填写）：查看 + 新增，六个清单仍是全部', async () => {
    const setup = await mount()
    setup.open()
    await flush()
    if (!setup.config) throw new Error('入口配置未打开')
    setup.config.objectId = 'orders'
    setup.config.mode = TaskEntryMode.FORM
    setup.chooseObject()
    await flush()
    const [main] = limits(setup)
    expect(main?.actions).toEqual(['READ', 'CREATE'])
    expect([main?.readFields, main?.writeFields, main?.writeDetails, main?.writeRelations]).toEqual([
      ['*'],
      ['*'],
      ['*'],
      ['*']
    ])
  })
  it('引用资料对象与关联录入对象：仅查看、全部记录，可查看全部、可修改为空', async () => {
    const setup = await mount()
    setup.open()
    await flush()
    if (!setup.config) throw new Error('入口配置未打开')
    setup.config.objectId = 'orders'
    setup.chooseObject()
    setup.config.formId = 'form'
    setup.syncFormObjects()
    setup.chooseDependencies(['customers'])
    await flush()
    const dependency = (objectId: string) => ({
      objectId,
      actions: ['READ'],
      scope: 'ALL',
      readFields: ['*'],
      writeFields: [],
      readDetails: ['*'],
      writeDetails: [],
      readRelations: ['*'],
      writeRelations: [],
      actionScopes: {}
    })
    expect(limits(setup).slice(1)).toEqual([dependency('customers'), dependency('contracts')])
  })
  it('引用资料对象的三个可修改清单不提供「全部」；主对象和关联录入对象提供', async () => {
    const setup = await mount()
    setup.open()
    await flush()
    if (!setup.config) throw new Error('入口配置未打开')
    setup.config.objectId = 'orders'
    setup.chooseObject()
    setup.config.formId = 'form'
    setup.syncFormObjects()
    setup.chooseDependencies(['customers'])
    await flush()
    expect(writeAll()).toEqual({ orders: 'true', customers: 'false', contracts: 'true' })
  })
})
