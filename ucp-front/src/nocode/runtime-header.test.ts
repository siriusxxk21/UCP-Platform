// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import Runtime from '@/views/nocode/application/runtime.vue'
import { applicationWorkShelfKey, type ApplicationWorkShelf } from './work-context'
import { runtimeNavigationKey } from './runtime-navigation'

const api = vi.hoisted(() => ({ application: vi.fn(), model: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: { render: () => h('div', { 'data-records': true }) }
}))
vi.mock('@/views/nocode/application/components/BusinessBlock.vue', () => ({
  default: { render: () => h('div', { 'data-block': true }) }
}))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({
  default: defineComponent({
    props: ['open'],
    setup: props => () => h('aside', { 'data-work-open': String(!!props.open) })
  })
}))
vi.mock('@/views/nocode/application/components/WorkShelf.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkDraftPanel.vue', () => ({ default: { render: () => null } }))

const plain = (tag: string) =>
  defineComponent({
    inheritAttrs: false,
    setup:
      (_, { slots, attrs }) =>
      () =>
        h(tag, attrs, slots.default?.())
  })
const view = (id: string): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  code: id,
  name: id,
  config: { objectId: 'voucher', fieldIds: [] }
})
const menu = (id: string, targetId: string): ApplicationResource => ({
  id,
  kind: ResourceKind.MENU,
  code: id,
  name: id,
  config: { targetId }
})
function fixture(resources: ApplicationResource[], warnings: string[] = []): RuntimeApplication {
  return {
    application: {
      id: 'app',
      code: 'finance',
      name: '财务',
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 1,
      publishedVersion: 1,
      updateTime: ''
    },
    versionNo: 1,
    checksum: 'published',
    warnings,
    definition: {
      objects: [{ objectId: 'voucher', versionNo: 1, checksum: 'voucher' }],
      resources
    }
  }
}

let app: App | undefined, host: HTMLDivElement
const workShelf = ref<ApplicationWorkShelf>()
async function flush() {
  for (let index = 0; index < 12; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(resources: ApplicationResource[], warnings: string[] = []) {
  api.application.mockResolvedValue(fixture(resources, warnings))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/nocode-app/runtime', component: { render: () => null } }]
  })
  await router.push({ path: '/nocode-app/runtime', query: { id: 'app' } })
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(Runtime)
  app.provide(applicationWorkShelfKey, workShelf)
  app.provide(runtimeNavigationKey, ref())
  app.use(router)
  app.component('a-spin', plain('div'))
  app.component('a-alert', plain('div'))
  app.component('a-empty', plain('div'))
  app.component(
    'a-select',
    defineComponent({
      props: ['value', 'options'],
      setup: () => () => h('select')
    })
  )
  app.component(
    'a-button',
    defineComponent({
      props: ['size'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { 'data-size': props.size || 'default' }, slots.default?.())
    })
  )
  app.mount(host)
  await flush()
}
const header = () => host.querySelector<HTMLElement>('.application-header')!
/** 页头一行里从左到右是什么 */
const row = () => Array.from(header().children).map(child => child.className.split(' ')[0])

beforeEach(() => {
  vi.clearAllMocks()
  workShelf.value = undefined
  api.model.mockResolvedValue({ object: { objectName: '凭证' } })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('application runtime page header', () => {
  it('旧应用仍保留页面切换，草稿按钮不再占用业务页头', async () => {
    await mount([view('vouchers'), view('flows'), menu('m1', 'vouchers'), menu('m2', 'flows')])
    expect(row()).toEqual(['application-menu'])
    expect(header().classList.contains('application-header--tabs')).toBe(true)
    expect(header().querySelectorAll('.application-menu-item')).toHaveLength(2)
    expect(host.querySelectorAll('.work-entry')).toHaveLength(0)
  })

  it('单页面不再为了草稿入口留下空工具行', async () => {
    await mount([view('vouchers'), menu('m1', 'vouchers')])
    expect(header()).toBeNull()
    expect(host.querySelector('.application-menu')).toBeNull()
  })

  it('无导航的旧应用仍可选择对象及视图', async () => {
    await mount([view('vouchers'), view('audit')])
    expect(row()).toEqual(['application-object-toolbar'])
    const toolbar = header().querySelector('.application-object-toolbar')!
    expect(toolbar.querySelector('[aria-label="业务对象"]')).not.toBeNull()
    expect(toolbar.querySelector('[aria-label="业务视图"]')).not.toBeNull()
    // 下拉只在页头这一处
    expect(host.querySelectorAll('.application-object-toolbar')).toHaveLength(1)
    expect(host.querySelector('[data-block]')).not.toBeNull()
  })

  it('版本警告继续显示，账号菜单提供的入口仍打开当前应用草稿面板', async () => {
    await mount([view('vouchers'), view('flows'), menu('m1', 'vouchers'), menu('m2', 'flows')], ['对象结构已调整'])
    const warning = host.querySelector('.runtime-warning')!
    expect(warning.compareDocumentPosition(header()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(row()).toEqual(['application-menu'])

    expect(host.querySelector<HTMLElement>('[data-work-open]')!.dataset.workOpen).toBe('false')
    expect(workShelf.value?.applicationId).toBe('app')
    workShelf.value?.open()
    await flush()
    expect(host.querySelector<HTMLElement>('[data-work-open]')!.dataset.workOpen).toBe('true')
    app?.unmount()
    app = undefined
    expect(workShelf.value).toBeUndefined()
  })

  it('采用平台菜单的应用不显示重复导航或空工具行', async () => {
    await mount([
      view('vouchers'),
      view('flows'),
      { ...menu('m1', 'vouchers'), config: { targetId: 'vouchers', navigationVersion: 2, showInMenu: true } },
      { ...menu('m2', 'flows'), config: { targetId: 'flows', navigationVersion: 2, showInMenu: true } }
    ])
    expect(header()).toBeNull()
    expect(workShelf.value?.applicationId).toBe('app')
    expect(host.querySelector('[data-block]')).not.toBeNull()
  })
})
