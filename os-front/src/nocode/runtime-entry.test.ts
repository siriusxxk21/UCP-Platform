// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import Runtime from '@/views/nocode/application/runtime.vue'
import { resolveViewForm } from '@/nocode/default-form'
import { runtimeNavigationKey, type RuntimeNavigationContext } from '@/nocode/runtime-navigation'

const api = vi.hoisted(() => ({ application: vi.fn(), model: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
// 保留真实入口与 BusinessBlock；表单解析现由接收完整发布资源的 BusinessRecords 负责。
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: defineComponent({
    props: ['applicationId', 'objectId', 'viewId', 'view', 'resources', 'initialRecordId'],
    setup: props => () =>
      h('div', {
        'data-records': true,
        'data-initial-record-id': props.initialRecordId,
        'data-object-id': props.objectId,
        'data-view-id': props.viewId,
        'data-form-id': props.view?.formId,
        'data-form-marker': resolveViewForm(props.resources || [], props.objectId, props.view?.formId)?.config.marker
      })
  })
}))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkShelf.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkDraftPanel.vue', () => ({ default: { render: () => null } }))

const passThrough = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.())
})
const Select = defineComponent({
  props: ['value', 'options'],
  emits: ['change'],
  setup:
    (props, { emit }) =>
    () =>
      h(
        'select',
        {
          value: props.value,
          onChange: (event: Event) => emit('change', (event.target as HTMLSelectElement).value)
        },
        props.options.map((option: { value: string; label: string }) =>
          h('option', { value: option.value }, option.label)
        )
      )
})
const form = (id: string, objectId: string): ApplicationResource => ({
  id,
  kind: ResourceKind.FORM,
  code: id,
  name: id,
  config: { objectId, nodes: [], detailIds: [], marker: id }
})
const view = (id: string, objectId: string, formId: string): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  code: id,
  name: id,
  config: { objectId, formId }
})
function fixture(resources: ApplicationResource[]): RuntimeApplication {
  return {
    application: {
      id: 'app',
      code: 'accounting',
      name: '会计登记凭证',
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 1,
      publishedVersion: 1,
      updateTime: ''
    },
    versionNo: 1,
    checksum: 'published',
    warnings: [],
    definition: {
      objects: ['voucher', 'subject', 'unconfigured'].map(objectId => ({ objectId, versionNo: 1, checksum: objectId })),
      resources
    }
  }
}
let app: App | undefined, host: HTMLDivElement, router: Router
async function flush() {
  for (let index = 0; index < 12; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
const navigation = ref<RuntimeNavigationContext>()
async function mount(resources: ApplicationResource[], query: Record<string, string> = {}, withSidebar = false) {
  api.application.mockResolvedValue(fixture(resources))
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/nocode-app/runtime', component: { render: () => null } }]
  })
  await router.push({ path: '/nocode-app/runtime', query: { id: 'app', ...query } })
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(Runtime)
  app.use(router)
  if (withSidebar) app.provide(runtimeNavigationKey, navigation)
  app.component('a-spin', passThrough)
  app.component('a-select', Select)
  app.component(
    'a-button',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component('a-alert', passThrough)
  app.component('a-empty', passThrough)
  app.component('a-statistic', passThrough)
  app.component('a-result', passThrough)
  app.component('a-card', passThrough)
  app.mount(host)
  await flush()
}
async function choose(label: string, value: string) {
  const select = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)
  if (!select) throw new Error(`missing ${label} selector`)
  const navigated = new Promise<void>(resolve => {
    const remove = router.afterEach(() => {
      remove()
      resolve()
    })
  })
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await navigated
  await flush()
}
function records() {
  const list = host.querySelector<HTMLElement>('[data-records]')
  if (!list) throw new Error('missing record list')
  return list
}
beforeEach(() => {
  navigation.value = undefined
  vi.clearAllMocks()
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => ({
    object: { objectName: objectId }
  }))
})

describe('published application page routes', () => {
  const configured = (id: string, targetId: string, defaultHome = false): ApplicationResource => ({
    id,
    kind: ResourceKind.MENU,
    code: id,
    name: id,
    config: { targetId, navigationVersion: 2, showInMenu: true, defaultHome }
  })

  it('opens a view directly without requiring a separate MENU resource', async () => {
    await mount([view('first', 'voucher', ''), view('direct', 'voucher', '')], { page: 'direct' })
    expect(records().dataset.viewId).toBe('direct')
  })

  it('resolves the configured homepage and supplies sidebar navigation without duplicate header tabs', async () => {
    await mount(
      [
        view('first', 'voucher', ''),
        view('home', 'voucher', ''),
        configured('m1', 'first'),
        configured('m2', 'home', true)
      ],
      {},
      true
    )
    expect(records().dataset.viewId).toBe('home')
    await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe('home'))
    expect(navigation.value?.items.map(item => item.id)).toEqual(['first', 'home'])
    expect(host.querySelector('.application-menu')).toBeNull()
    app?.unmount()
    app = undefined
    expect(navigation.value).toBeUndefined()
  })

  it('keeps old menu deep links but does not fall back from an invalid explicit page', async () => {
    await mount([view('first', 'voucher', ''), view('direct', 'voucher', ''), configured('m1', 'direct')], {
      menu: 'm1'
    })
    expect(records().dataset.viewId).toBe('direct')
    await router.push({ path: '/nocode-app/runtime', query: { id: 'app', page: 'deleted' } })
    await flush()
    expect(host.querySelector('[data-records]')).toBeNull()
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('published application entry without navigation', () => {
  it('uses the object view and its published form instead of the generic record list', async () => {
    await mount([form('voucher-form', 'voucher'), view('voucher-view', 'voucher', 'voucher-form')])
    expect(records().dataset.objectId).toBe('voucher')
    expect(records().dataset.viewId).toBe('voucher-view')
    expect(records().dataset.formId).toBe('voucher-form')
    expect(records().dataset.formMarker).toBe('voucher-form')
    expect(host.querySelector('[aria-label="业务视图"]')).toBeNull()
  })

  it('switches between published views and their respective form bindings', async () => {
    await mount([
      form('voucher-form', 'voucher'),
      form('audit-form', 'voucher'),
      view('voucher-view', 'voucher', 'voucher-form'),
      view('audit-view', 'voucher', 'audit-form')
    ])
    expect(records().dataset.viewId).toBe('voucher-view')
    await choose('业务视图', 'audit-view')
    expect(router.currentRoute.value.query.viewId).toBe('audit-view')
    expect(records().dataset.viewId).toBe('audit-view')
    expect(records().dataset.formMarker).toBe('audit-form')
  })

  it('honors a deep-linked view only when it belongs to the selected object', async () => {
    await mount(
      [
        form('voucher-form', 'voucher'),
        view('voucher-view', 'voucher', 'voucher-form'),
        view('audit-view', 'voucher', 'voucher-form'),
        form('subject-form', 'subject'),
        view('subject-view', 'subject', 'subject-form')
      ],
      { viewId: 'audit-view' }
    )
    expect(records().dataset.viewId).toBe('audit-view')
    await choose('业务对象', 'subject')
    expect(records().dataset.objectId).toBe('subject')
    expect(records().dataset.viewId).toBe('subject-view')
    expect(records().dataset.formMarker).toBe('subject-form')
    await router.replace({ query: { id: 'app', objectId: 'subject', viewId: 'audit-view' } })
    await flush()
    expect(records().dataset.viewId).toBe('subject-view')
  })

  it('uses the generic list only for an object without a published view', async () => {
    await mount([form('voucher-form', 'voucher'), view('voucher-view', 'voucher', 'voucher-form')])
    await choose('业务对象', 'unconfigured')
    expect(records().dataset.objectId).toBe('unconfigured')
    expect(records().dataset.viewId).toBeUndefined()
    expect(records().dataset.formId).toBeUndefined()
    expect(host.querySelector('[aria-label="业务视图"]')).toBeNull()
  })

  it('preserves the configured navigation entry instead of selecting an object fallback', async () => {
    await mount(
      [
        form('voucher-form', 'voucher'),
        view('voucher-view', 'voucher', 'voucher-form'),
        form('subject-form', 'subject'),
        view('subject-view', 'subject', 'subject-form'),
        {
          id: 'subject-menu',
          code: 'subject-menu',
          kind: ResourceKind.MENU,
          name: '会计科目',
          config: { targetId: 'subject-view' }
        }
      ],
      { objectId: 'voucher', viewId: 'voucher-view', menu: 'subject-menu' }
    )
    expect(records().dataset.objectId).toBe('subject')
    expect(records().dataset.viewId).toBe('subject-view')
    expect(records().dataset.formMarker).toBe('subject-form')
    expect(host.querySelector('[aria-label="业务对象"]')).toBeNull()
    expect(host.querySelector('[aria-label="业务视图"]')).toBeNull()
    expect(api.model).not.toHaveBeenCalled()
  })
})

describe('业务文件返回业务记录', () => {
  it('对象深链跳过其他对象的默认菜单，并保留目标发布视图和表单', async () => {
    await mount(
      [
        form('voucher-form', 'voucher'),
        view('voucher-view', 'voucher', 'voucher-form'),
        form('subject-form', 'subject'),
        view('subject-view', 'subject', 'subject-form'),
        { id: 'first-menu', kind: ResourceKind.MENU, code: 'first', name: '凭证', config: { targetId: 'voucher-view' } }
      ],
      { objectId: 'subject', recordId: '7' }
    )
    const records = host.querySelector('[data-records]')
    expect(records?.getAttribute('data-object-id')).toBe('subject')
    expect(records?.getAttribute('data-initial-record-id')).toBe('7')
    expect(records?.getAttribute('data-form-id')).toBe('subject-form')
    await router.push({ path: '/nocode-app/runtime', query: { id: 'app', objectId: 'subject', recordId: '8' } })
    await flush()
    expect(host.querySelector('[data-records]')?.getAttribute('data-initial-record-id')).toBe('8')
  })
  it('没有发布视图时也把指定记录交给已有详情入口', async () => {
    await mount([], { objectId: 'unconfigured', recordId: '4' })
    expect(host.querySelector('[data-records]')?.getAttribute('data-initial-record-id')).toBe('4')
  })
})
