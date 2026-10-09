// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, watch, type App } from 'vue'
import Antd from 'ant-design-vue'
import type { ApplicationResource } from '@/types/nocode/application'
import type { FormConfig } from '@/types/nocode/application-ui'
import { NodeKind, PageActionKind, uiNode } from '@/types/nocode/application-ui'

const api = vi.hoisted(() => ({ model: vi.fn(), page: vi.fn(), get: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'record-opening-test' } }) }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [
          slots.actions?.(),
          ...props.dataSource.map((record: any) =>
            h('section', { 'data-row': record.id }, slots.bodyCell?.({ record, column: { key: 'actions' } }))
          )
        ])
  })
}))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({
  default: defineComponent({
    props: ['open'],
    emits: ['update:open'],
    setup:
      (props, { emit, slots }) =>
      () =>
        props.open
          ? h('aside', [h('button', { onClick: () => emit('update:open', false) }, '关闭窗口'), slots.default?.()])
          : null
  })
}))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: { record: Object, form: Object, formId: String, readOnly: Boolean },
    setup(props) {
      const draft = ref('')
      watch(
        () => props.record,
        () => {
          draft.value = props.record?.record.values.name || ''
        },
        { immediate: true }
      )
      return () =>
        h('input', {
          'data-editor-id': props.record?.record.id || 'new',
          'data-form-id': props.formId || '',
          'data-form-submit-text': props.form?.options?.submitText || '',
          'data-read-only': String(!!props.readOnly),
          value: draft.value,
          onInput: (event: Event) => {
            draft.value = (event.target as HTMLInputElement).value
          }
        })
    }
  })
}))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({
  __esModule: true,
  default: defineComponent({
    props: ['pageId'],
    setup: props => () => h('div', { 'data-detail-page': props.pageId })
  })
}))
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'
import PageActionButton from '@/views/nocode/application/components/PageActionButton.vue'

const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string) => ({ id, revision: '1', values: { name: id }, permissions })
const record = (id: string) => ({ record: row(id), details: {} })
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function mount(props: Record<string, unknown> = {}) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(BusinessRecords, { applicationId: 'app', objectId: 'object', ...props }))
  app.use(Antd)
  app.mount(host)
  await flush()
}
function click(id: string, action = '编辑') {
  Array.from(host.querySelectorAll<HTMLButtonElement>(`section[data-row="${id}"] button`))
    .find(button => button.textContent?.includes(action))!
    .click()
}
beforeEach(() => {
  api.model.mockResolvedValue({
    writable: true,
    permissions,
    object: {
      objectId: 'object',
      objectName: '对象',
      fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
      fieldOptions: {},
      details: [],
      relations: [],
      settings: {}
    },
    details: {}
  })
  api.page.mockResolvedValue({ list: [row('A'), row('B')], total: 2 })
})
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('列表开窗请求会话', () => {
  it.each(['编辑', '查看'])('A迟到不替换B的%s窗口及输入', async action => {
    const a = deferred<any>(),
      b = deferred<any>()
    api.get.mockImplementation((_app, _object, id) => (id === 'A' ? a.promise : b.promise))
    await mount()
    click('A', action)
    click('B', action)
    b.resolve(record('B'))
    await flush()
    const input = host.querySelector<HTMLInputElement>('[data-editor-id="B"]')!
    expect(input).toBeTruthy()
    input.value = '未保存输入'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    a.resolve(record('A'))
    await flush()
    expect(host.querySelector('[data-editor-id="A"]')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('[data-editor-id="B"]')!.value).toBe('未保存输入')
  })
  it('B窗口关闭后，旧A响应不会重开窗口', async () => {
    const a = deferred<any>()
    api.get.mockImplementation((_app, _object, id) => (id === 'A' ? a.promise : Promise.resolve(record('B'))))
    await mount()
    click('A')
    click('B')
    await flush()
    host.querySelector<HTMLButtonElement>('aside button')!.click()
    await flush()
    a.resolve(record('A'))
    await flush()
    expect(host.querySelector('aside')).toBeNull()
  })
  it('旧打开请求失败不覆盖当前窗口的错误状态', async () => {
    const a = deferred<any>()
    api.get.mockImplementation((_app, _object, id) => (id === 'A' ? a.promise : Promise.resolve(record('B'))))
    await mount()
    click('A')
    click('B')
    await flush()
    a.reject(new Error('旧请求失败'))
    await flush()
    expect(host.textContent).not.toContain('旧请求失败')
    expect(host.querySelector('[data-editor-id="B"]')).toBeTruthy()
  })
})

function form(id: string, options: Partial<NonNullable<FormConfig['options']>> = {}): ApplicationResource {
  return {
    id,
    code: id,
    name: id,
    kind: 'FORM',
    config: {
      objectId: 'object',
      nodes: [],
      detailIds: [],
      options: { layout: 'vertical', submitText: id, ...options }
    }
  }
}
const view = (formId: string | null = null) => ({ objectId: 'object', fieldIds: ['name'], formId })
function editor() {
  return host.querySelector<HTMLInputElement>('[data-editor-id]')
}

async function mountPageCreate(formId: string | null = null, readOnly = false) {
  const target = uiNode(NodeKind.VIEW, { id: 'list', resourceId: 'view' })
  const button = uiNode(NodeKind.BUTTON, {
    text: '新增记录',
    action: { kind: PageActionKind.CREATE, targetNodeId: 'list' }
  })
  const resources: ApplicationResource[] = [
    form('default-form', { defaultForObject: true, readOnly }),
    { id: 'view', code: 'view', name: '列表', kind: 'VIEW', config: view(formId) },
    { id: 'page', code: 'page', name: '页面', kind: 'PAGE', config: { nodes: [target, button] } }
  ]
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(PageActionButton, { node: button, applicationId: 'app', pageId: 'page', resources }))
  app.use(Antd)
  app.mount(host)
  await flush()
  return host.querySelector<HTMLButtonElement>('button')!
}

describe('业务页面新增按钮沿用列表表单', () => {
  it('向记录编辑器传入默认表单的真实 ID 和配置', async () => {
    const button = await mountPageCreate()
    expect(button.disabled).toBe(false)
    button.click()
    await flush()
    expect(editor()?.dataset.formId).toBe('default-form')
    expect(editor()?.dataset.formSubmitText).toBe('default-form')
  })
  it('显式绑定失效时禁止打开或请求替代表单', async () => {
    const button = await mountPageCreate('deleted')
    expect(button.disabled).toBe(true)
    button.click()
    await flush()
    expect(editor()).toBeNull()
    expect(api.model).not.toHaveBeenCalled()
  })
  it('默认表单只读时不允许通过页面按钮新增', async () => {
    const button = await mountPageCreate(null, true)
    expect(button.disabled).toBe(true)
    button.click()
    await flush()
    expect(editor()).toBeNull()
  })
})

describe('列表实际打开默认表单与受控入口边界', () => {
  beforeEach(() => {
    api.get.mockImplementation((_app, _object, id) => Promise.resolve(record(id)))
  })

  it.each(['新增', '编辑', '查看'])('%s 传递默认表单的真实资源 ID 与配置', async action => {
    await mount({ view: view(), resources: [form('default-form', { defaultForObject: true })] })
    if (action === '新增') {
      const create = Array.from(host.querySelectorAll('button')).find(button => button.textContent?.includes('新增'))
      expect(create).toBeTruthy()
      create?.click()
    } else click('A', action)
    await flush()
    expect(editor()?.dataset.formId).toBe('default-form')
    expect(editor()?.dataset.formSubmitText).toBe('default-form')
    expect(editor()?.dataset.readOnly).toBe(String(action === '查看'))
  })
  it('列表显式绑定继续打开自身表单', async () => {
    await mount({
      view: view('own-form'),
      resources: [form('default-form', { defaultForObject: true }), form('own-form')]
    })
    click('A')
    await flush()
    expect(editor()?.dataset.formId).toBe('own-form')
    expect(editor()?.dataset.formSubmitText).toBe('own-form')
  })
  it('旧发布普通表单不被隐式使用，仍保留自动表单', async () => {
    await mount({ view: view(), resources: [form('ordinary')] })
    click('A')
    await flush()
    expect(editor()?.dataset.formId).toBe('')
    expect(editor()?.dataset.formSubmitText).toBe('')
  })
  it('无有效显式表单时展示配置错误，不能偷偷用默认表单编辑', async () => {
    await mount({ view: view('missing'), resources: [form('default-form', { defaultForObject: true })] })
    expect(host.textContent).toContain('列表指定的业务表单不存在')
    expect(host.querySelector('section[data-row="A"]')?.textContent).not.toContain('编辑')
    click('A', '查看')
    await flush()
    expect(editor()).toBeNull()
    expect(api.get).not.toHaveBeenCalled()
  })
  it('只读默认表单收紧新增和编辑按钮', async () => {
    await mount({ view: view(), resources: [form('default-form', { defaultForObject: true, readOnly: true })] })
    expect(host.textContent).not.toContain('新增')
    expect(host.querySelector('section[data-row="A"]')?.textContent).not.toContain('编辑')
    click('A', '查看')
    await flush()
    expect(editor()?.dataset.formId).toBe('default-form')
  })
  it('独立详情页优先于默认表单', async () => {
    await mount({
      view: { ...view(), detailPageId: 'detail-page' },
      resources: [
        form('default-form', { defaultForObject: true }),
        { id: 'detail-page', code: 'detail', name: '详情页', kind: 'PAGE', config: { nodes: [] } }
      ]
    })
    click('A', '查看')
    await vi.waitFor(() => expect(host.querySelector('[data-detail-page="detail-page"]')).toBeTruthy())
    expect(editor()).toBeNull()
  })
  it('任务列表明确无表单时不继承对象默认，也不采用列表自身绑定', async () => {
    await mount({
      view: view('view-form'),
      resources: [form('default-form', { defaultForObject: true }), form('view-form')],
      formId: null,
      inheritDefaultForm: false
    })
    click('A')
    await flush()
    expect(editor()?.dataset.formId).toBe('')
    expect(editor()?.dataset.formSubmitText).toBe('')
  })
  it('任务列表显式冻结表单优先于列表自身和对象默认', async () => {
    await mount({
      view: view('view-form'),
      resources: [form('default-form', { defaultForObject: true }), form('view-form'), form('task-form')],
      formId: 'task-form',
      inheritDefaultForm: false
    })
    click('A')
    await flush()
    expect(editor()?.dataset.formId).toBe('task-form')
    expect(editor()?.dataset.formSubmitText).toBe('task-form')
  })
})
