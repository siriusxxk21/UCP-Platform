// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, provide, ref, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { createPinia } from 'pinia'
import Runtime from '@/views/nocode/application/runtime.vue'
import { KeptPages } from './kept-pages'
import KeptRouteView from './kept-route-view'
import { runtimeNavigationKey, type RuntimeNavigationContext } from './runtime-navigation'
import { applicationWorkShelfKey, type ApplicationWorkShelf } from './work-context'
import type { ApplicationResource } from '@/types/nocode/application'
import type { RuntimeApplication } from '@/types/nocode/runtime'

const api = vi.hoisted(() => ({ application: vi.fn(), model: vi.fn() }))
const probe = vi.hoisted(() => ({ trackUnsaved: false, confirm: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: probe.confirm } }))
vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    userInfo: { id: 'review-user' },
    menus: [
      {
        id: 'orders',
        parentId: '0',
        name: '订单',
        path: '/nocode-app/runtime?id=A&page=orders',
        component: 'nocode/application/runtime',
        sort: 1
      }
    ]
  })
}))
vi.mock('@/stores/navigationTabs', () => ({ useNavigationTabsStore: () => ({ tabs: [{ key: 'system:orders' }] }) }))
vi.mock('@/views/nocode/application/components/BusinessBlock.vue', async () => {
  const { defineComponent, h, ref } = await import('vue')
  const { useUnsavedNavigation } = await import('./unsaved')
  return {
    default: defineComponent({
      props: ['applicationId', 'resourceId', 'initialRecordId'],
      setup(props) {
        const edits = ref(0)
        if (probe.trackUnsaved) useUnsavedNavigation(() => edits.value > 0)
        return () =>
          h(
            'button',
            {
              'data-block': `${props.applicationId}:${props.resourceId}`,
              'data-record': props.initialRecordId,
              onClick: () => edits.value++
            },
            `输入 ${edits.value}`
          )
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/PageRenderer.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['pageId', 'recordId'],
      setup: props => () => h('article', { 'data-page': props.pageId, 'data-record': props.recordId })
    })
  }
})
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({
  default: defineComponent({
    props: ['open'],
    setup: props => () => h('aside', { 'data-work-open': String(!!props.open) })
  })
}))
vi.mock('@/views/nocode/application/components/WorkShelf.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkDraftPanel.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ApplicationDashboardBlock.vue', () => ({
  default: { render: () => null }
}))

const view = (id = 'orders'): ApplicationResource => ({
  id,
  kind: 'VIEW',
  name: id,
  code: id,
  config: { objectId: 'object' }
})
const menu = (targetId: string, config: Record<string, unknown> = {}): ApplicationResource => ({
  id: 'menu',
  kind: 'MENU',
  name: '业务页面',
  code: 'menu',
  config: { targetId, navigationVersion: 2, showInMenu: true, ...config }
})
function fixture(id: string, resources: ApplicationResource[]): RuntimeApplication {
  return {
    application: {
      id,
      code: id,
      name: `应用 ${id}`,
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 1,
      publishedVersion: 1,
      updateTime: ''
    },
    definition: { objects: [{ objectId: 'object', versionNo: 1, checksum: 'object' }], resources },
    versionNo: 1,
    checksum: id,
    warnings: []
  }
}
const navigation = ref<RuntimeNavigationContext>()
const workShelf = ref<ApplicationWorkShelf>()
let app: App | undefined, host: HTMLDivElement, router: Router
async function flush() {
  for (let turn = 0; turn < 12; turn++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(query: Record<string, string>, kept = false) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/nocode-app/runtime', component: { render: () => null } }]
  })
  await router.push({ path: '/nocode-app/runtime', query: { id: 'A', ...query } })
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(
    defineComponent({
      setup() {
        provide(runtimeNavigationKey, navigation)
        provide(applicationWorkShelfKey, workShelf)
        return () =>
          kept
            ? h(
                KeptPages,
                {
                  pageKey: String(router.currentRoute.value.query.id),
                  max: 3,
                  stays: () => true,
                  routeKey: route => String(route.query.id)
                },
                () => h(Runtime)
              )
            : h(Runtime)
      }
    })
  )
  app.use(router)
  app.use(createPinia())
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const kind of ['spin', 'select', 'button']) app.component(`a-${kind}`, plain)
  app.component('a-alert', defineComponent({ props: ['message'], setup: props => () => h('p', props.message) }))
  app.component(
    'a-empty',
    defineComponent({ props: ['description'], setup: props => () => h('p', { 'data-empty': true }, props.description) })
  )
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  probe.trackUnsaved = false
  navigation.value = undefined
  workShelf.value = undefined
  api.model.mockResolvedValue({ object: { objectName: '对象' } })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('页面导航复核：显式地址与保活边界', () => {
  it('隐藏页面切到同应用的平台菜单会销毁原宿主，必须保留未保存确认', async () => {
    probe.trackUnsaved = true
    probe.confirm.mockImplementation(options => options.onCancel())
    api.application.mockResolvedValue(fixture('A', [view(), view('hidden'), menu('orders')]))
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/nocode-app/runtime', component: Runtime }]
    })
    await router.push('/nocode-app/runtime?id=A&page=hidden')
    await router.isReady()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() =>
      h(
        RouterView,
        {},
        { default: ({ Component }: { Component: import('vue').VNode }) => h(KeptRouteView, { component: Component }) }
      )
    )
    app.use(router)
    app.use(createPinia())
    for (const name of ['spin', 'select', 'button', 'alert', 'empty'])
      app.component(
        `a-${name}`,
        defineComponent({
          setup:
            (_, { slots }) =>
            () =>
              h('div', slots.default?.())
        })
      )
    app.mount(host)
    await flush()
    const hidden = host.querySelector<HTMLButtonElement>('[data-block="A:hidden"]')
    expect(hidden).not.toBeNull()
    hidden?.click()
    await flush()
    await router.push('/nocode-app/runtime?id=A&page=orders')
    await flush()
    expect(probe.confirm).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.query.page).toBe('hidden')
    expect(host.querySelector('[data-block="A:hidden"]')).toBe(hidden)
    expect(hidden?.textContent).toBe('输入 1')
    probe.confirm.mockImplementation(options => options.onOk())
    await router.push('/nocode-app/runtime?id=A&page=orders')
    await flush()
    expect(probe.confirm).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.query.page).toBe('orders')
    expect(host.querySelector('[data-block="A:hidden"]')).toBeNull()
    expect(host.querySelector('[data-block="A:orders"]')).not.toBeNull()
  })
  it('后台应用不能覆盖当前应用侧栏，跨应用同名资源仍分别保留编辑状态', async () => {
    api.application.mockImplementation(async (id: string) => fixture(id, [view(), menu('orders')]))
    await mount({ page: 'orders' }, true)
    const previousWorkShelf = workShelf.value
    const first = host.querySelector<HTMLButtonElement>('[data-block="A:orders"]')
    expect(first).not.toBeNull()
    first?.click()
    await flush()
    await router.push('/nocode-app/runtime?id=B&page=orders')
    await flush()
    expect(navigation.value?.applicationId).toBe('B')
    expect(workShelf.value?.applicationId).toBe('B')
    previousWorkShelf?.open()
    await flush()
    expect(host.querySelector('[data-work-open]')?.getAttribute('data-work-open')).toBe('false')
    expect(navigation.value?.items[0]?.path).toContain('id=B')
    expect(host.querySelector('[data-block="A:orders"]')).toBeNull()
    expect(host.querySelector('[data-block="B:orders"]')?.textContent).toBe('输入 0')
    await router.push('/nocode-app/runtime?id=A&page=orders')
    await flush()
    expect(navigation.value?.applicationId).toBe('A')
    expect(workShelf.value?.applicationId).toBe('A')
    expect(host.querySelector('[data-work-open]')?.getAttribute('data-work-open')).toBe('false')
    workShelf.value?.open()
    await flush()
    expect(host.querySelector('[data-work-open]')?.getAttribute('data-work-open')).toBe('true')
    expect(host.querySelector('[data-block="A:orders"]')).toBe(first)
    expect(first?.textContent).toBe('输入 1')
  })

  it.each(['page', 'menu'])('失效的显式 %s 地址不因附带记录参数回退其他业务页面', async selector => {
    api.application.mockResolvedValue(fixture('A', [view(), menu('orders')]))
    await mount({ [selector]: 'removed', objectId: 'object', recordId: '100' })
    expect(host.querySelector('[data-block]')).toBeNull()
    expect(host.querySelector('[data-empty]')?.textContent).toContain('页面已移除')
  })

  it('旧 MENU 深链与 page 深链保持相同上下文要求，不把记录详情页当独立页打开', async () => {
    const detail: ApplicationResource = {
      id: 'detail',
      kind: 'PAGE',
      name: '详情',
      code: 'detail',
      config: { nodes: [], contextObjectId: 'object' }
    }
    const legacy = menu('detail', { navigationVersion: undefined })
    api.application.mockResolvedValue(fixture('A', [detail, legacy]))
    await mount({ menu: 'menu' })
    expect(host.querySelector('[data-page]')).toBeNull()
    expect(host.querySelector('[data-empty]')?.textContent).toContain('页面已移除')
  })
})
