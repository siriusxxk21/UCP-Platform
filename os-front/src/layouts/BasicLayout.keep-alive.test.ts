// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, ref } from 'vue'
import { Modal } from 'ant-design-vue'
import type { App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import type { RouteLocationNormalized, Router } from 'vue-router'
import type { Menu } from '@/types'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import { ResourceKind } from '@/types/nocode/application'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import type { ApplicationResource } from '@/types/nocode/application'
import { resolveNavigationTab } from '@/router/navigationTabs'
import { useNavigationTabsStore } from '@/stores/navigationTabs'
import { useUserStore } from '@/stores/user'
import Runtime from '@/views/nocode/application/runtime.vue'
import BasicLayout from './BasicLayout.vue'

vi.mock('ant-design-vue', () => ({
  message: { info: vi.fn(), success: vi.fn(), warning: vi.fn() },
  Modal: { confirm: vi.fn() },
  notification: { info: vi.fn() }
}))
vi.mock('@/stores/user', async () => {
  const { reactive } = await import('vue')
  const { childMenus } = await import('@/utils/menuNavigation')
  const store = reactive({
    token: 'token',
    userInfo: { id: 'u1', username: 'tester' },
    tenantInfo: null,
    menus: [] as Menu[],
    get topMenus(): Menu[] {
      return this.menus.filter(menu => Number(menu.parentId) === 0)
    },
    getSideMenus(topMenuId: string | number): Menu[] {
      return childMenus(this.menus, topMenuId)
    },
    initDynamicRoutes: () => true,
    logout: () => undefined
  })
  return { useUserStore: () => store }
})
vi.mock('@/stores/realtime', () => ({ useRealtimeStore: () => ({ unreadCount: 0 }) }))
vi.mock('@/realtime', () => ({
  realtimeBus: { emit: vi.fn() },
  realtimeRuntime: { start: vi.fn() },
  useRealtimeEvent: vi.fn()
}))
vi.mock('@/api/auth', () => ({ logout: vi.fn() }))
vi.mock('@/components/AppIcon.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/navigation/NavigationTabs.vue', () => ({ default: { render: () => null } }))

const api = vi.hoisted(() => ({ application: vi.fn(), model: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
const log: string[] = []
/** 列表里有没有填了一半的表单（按「应用/视图」记） */
const dirty: Record<string, boolean> = {}
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: defineComponent({
    props: ['applicationId', 'objectId', 'viewId'],
    setup(props) {
      const name = `${props.applicationId}/${props.viewId || props.objectId}`
      const page = ref(1)
      log.push(`list:${name}`)
      onUnmounted(() => log.push(`gone:${name}`))
      useUnsavedNavigation(() => !!dirty[name])
      return () => h('button', { 'data-records': name, onClick: () => page.value++ }, `第 ${page.value} 页`)
    }
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
/** 普通页面：靠重建取最新数据的那一类。 */
const OrdinaryPage = defineComponent({
  setup() {
    log.push('page:user')
    onUnmounted(() => log.push('gone:user'))
    return () => h('p', { 'data-ordinary': true }, '用户管理')
  }
})

/** 「财务」挂在目录下，「建筑」自己就是一级页面；两个都是应用运行页，同一个组件。 */
const menus: Menu[] = [
  { id: '1', name: '系统', path: '/system', parentId: '0', sort: 0 },
  { id: '100', name: '用户管理', path: '/system/user', component: 'system/user/index', parentId: '1', sort: 0 },
  { id: '2', name: '业务测试', path: '/business', parentId: '0', sort: 1 },
  {
    id: '3054',
    name: '财务',
    path: '/nocode-app/runtime?id=3054',
    component: 'nocode/application/runtime',
    parentId: '2',
    sort: 0
  },
  {
    id: '3057',
    name: '建筑',
    path: '/nocode-app/runtime?id=3057',
    component: 'nocode/application/runtime',
    parentId: '0',
    sort: 2
  }
]
const FINANCE = 'system:3054'

function view(id: string, objectId: string): ApplicationResource {
  return { id, kind: ResourceKind.VIEW, code: id, name: id, config: { objectId, fieldIds: ['name'] } }
}
function menu(id: string, targetId: string): ApplicationResource {
  return { id, kind: ResourceKind.MENU, code: id, name: id, config: { targetId } }
}
/** 每个应用两个应用内菜单：凭证、流水 */
function definition(id: string): RuntimeApplication {
  return {
    application: {
      id,
      code: id,
      name: id,
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 1,
      publishedVersion: 1,
      updateTime: ''
    },
    versionNo: 1,
    checksum: id,
    warnings: [],
    definition: {
      objects: ['voucher', 'flow'].map(objectId => ({ objectId, versionNo: 1, checksum: objectId })),
      resources: [view('vouchers', 'voucher'), view('flows', 'flow'), menu('m1', 'vouchers'), menu('m2', 'flows')]
    }
  }
}

let app: App | undefined, host: HTMLDivElement, router: Router

async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}

function registerTab(to: RouteLocationNormalized, tabs: ReturnType<typeof useNavigationTabsStore>) {
  const tab = resolveNavigationTab(to, menus)
  if (tab) tabs.register(tab)
}

/** nested：布局本身作为一条路由的组件（正式环境的结构），登录页在布局之外。 */
async function mount(startPath: string, nested = false) {
  const pages = [
    { path: '/system/user/:id?', component: OrdinaryPage },
    { path: '/nocode-app/runtime/:id?', component: Runtime }
  ]
  const user = useUserStore()
  user.menus = menus
  user.token = 'token'
  user.userInfo = { id: 'u1', username: 'tester' } as typeof user.userInfo
  const pinia = createPinia()
  router = createRouter({
    history: createMemoryHistory(),
    routes: nested
      ? [
          { path: '/', component: BasicLayout, children: pages },
          { path: '/login', component: { render: () => h('p', '登录页') } }
        ]
      : pages
  })
  // 与正式路由相同：导航成功后登记页签
  router.afterEach(to => registerTab(to, useNavigationTabsStore(pinia)))
  await router.push(startPath)
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(nested ? { render: () => h(RouterView) } : BasicLayout)
  app.use(pinia)
  app.use(router)
  for (const name of [
    'tooltip',
    'button',
    'badge',
    'dropdown',
    'menu',
    'menu-item',
    'menu-divider',
    'spin',
    'select',
    'empty',
    'alert',
    'statistic',
    'result',
    'card'
  ])
    app.component(`a-${name}`, passThrough)
  app.mount(host)
  await flush()
  return useNavigationTabsStore(pinia)
}

/** 「放弃尚未保存的修改？」——用户选「继续编辑」。 */
function declineDiscard(options: Parameters<typeof Modal.confirm>[0]) {
  options.onCancel?.()
  return { destroy: vi.fn(), update: vi.fn() }
}

async function go(path: string) {
  await router.push(path)
  await flush()
}

const list = (name: string) => host.querySelector<HTMLElement>(`[data-records="${name}"]`)
const runtimePage = () => host.querySelector<HTMLElement>('.application-runtime')
const definitionCalls = (id: string) => api.application.mock.calls.filter(call => call[0] === id).length

describe('basic layout keeps application runtime pages alive per tab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    log.length = 0
    for (const name of Object.keys(dirty)) delete dirty[name]
    api.application.mockImplementation(async (id: string) => definition(id))
    api.model.mockImplementation(async (_applicationId: string, objectId: string) => ({
      object: { objectName: objectId }
    }))
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
  })

  it('does not rebuild the runtime page or ask for the definition again when moving inside an application', async () => {
    await mount('/nocode-app/runtime?id=3054')
    const page = runtimePage()!
    expect(list('3054/vouchers')).not.toBeNull()

    await go('/nocode-app/runtime?id=3054&menu=m2')
    expect(list('3054/flows')).not.toBeNull()
    await go('/nocode-app/runtime?id=3054&menu=m1')

    expect(runtimePage()).toBe(page)
    expect(definitionCalls('3054')).toBe(1)
    expect(log).toEqual(['list:3054/vouchers', 'list:3054/flows'])
  })

  it('brings a tab back exactly as it was left, comparing the definition in the background', async () => {
    await mount('/nocode-app/runtime?id=3054')
    list('3054/vouchers')!.click()
    await nextTick()
    const kept = list('3054/vouchers')!

    await go('/nocode-app/runtime?id=3057')
    expect(list('3057/vouchers')).not.toBeNull()
    expect(kept.isConnected).toBe(false)

    let answer!: (value: RuntimeApplication) => void
    api.application.mockImplementation(() => new Promise<RuntimeApplication>(resolve => (answer = resolve)))
    await go('/nocode-app/runtime?id=3054')
    // 定义请求还没回来，页面已经是离开时的样子
    expect(list('3054/vouchers')).toBe(kept)
    expect(kept.textContent).toBe('第 2 页')
    answer(definition('3054'))
    await flush()

    expect(list('3054/vouchers')).toBe(kept)
    expect(definitionCalls('3054')).toBe(2)
    expect(log).toEqual(['list:3054/vouchers', 'list:3057/vouchers'])
  })

  it('does not let a background tab follow the address of the tab in front', async () => {
    await mount('/nocode-app/runtime?id=3054')
    await go('/nocode-app/runtime?id=3057&menu=m2')
    await go('/system/user')
    // 后台的「财务」如果跟着读了「建筑」的地址，会再取一次建筑的定义、再建一个流水列表
    expect(definitionCalls('3057')).toBe(1)
    expect(definitionCalls('3054')).toBe(1)
    expect(log.filter(entry => entry.startsWith('list:'))).toEqual(['list:3054/vouchers', 'list:3057/flows'])
  })

  it('still rebuilds ordinary pages whenever the address changes', async () => {
    await mount('/system/user?x=1')
    await go('/system/user?x=2')
    await go('/nocode-app/runtime?id=3054')
    await go('/system/user?x=2')
    // 每次地址变化都是一个新实例，旧的销毁
    expect(log.filter(entry => entry === 'page:user')).toHaveLength(3)
    expect(log.filter(entry => entry === 'gone:user')).toHaveLength(2)
  })

  it('releases a kept page when its tab is closed', async () => {
    const tabs = await mount('/nocode-app/runtime?id=3054')
    await go('/nocode-app/runtime?id=3057')
    expect(log).not.toContain('gone:3054/vouchers')

    tabs.close(FINANCE)
    await flush()
    expect(log).toContain('gone:3054/vouchers')

    await go('/nocode-app/runtime?id=3054')
    expect(log.filter(entry => entry === 'list:3054/vouchers')).toHaveLength(2)
  })

  it('drops every kept page and cached definition when the signed-in identity changes or signs out', async () => {
    await mount('/nocode-app/runtime?id=3054')
    await go('/nocode-app/runtime?id=3057')
    const user = useUserStore()

    user.userInfo = { id: 'u2', username: 'another' } as typeof user.userInfo
    await flush()
    expect(log).toContain('gone:3054/vouchers')
    expect(log).toContain('gone:3057/vouchers')
    // 定义缓存也清了：新身份进入「财务」要重新取，而且是先取后画
    const before = definitionCalls('3054')
    let answer!: (value: RuntimeApplication) => void
    api.application.mockImplementation(() => new Promise<RuntimeApplication>(resolve => (answer = resolve)))
    await go('/nocode-app/runtime?id=3054')
    expect(definitionCalls('3054')).toBe(before + 1)
    expect(list('3054/vouchers')).toBeNull()
    answer(definition('3054'))
    await flush()
    expect(list('3054/vouchers')).not.toBeNull()

    log.length = 0
    user.token = ''
    await flush()
    expect(log).toContain('gone:3054/vouchers')
    expect(runtimePage()).toBeNull()
  })

  it('keeps a half-filled form across tabs without asking, and asks only when leaving would destroy it', async () => {
    // 用户每次都选「继续编辑」
    vi.mocked(Modal.confirm).mockImplementation(declineDiscard)
    await mount('/nocode-app/runtime?id=3054', true)
    dirty['3054/vouchers'] = true

    // 切到别的页签、应用内切菜单：表单留着，不问
    await go('/nocode-app/runtime?id=3057')
    await go('/system/user')
    await go('/nocode-app/runtime?id=3054&menu=m2')
    await go('/nocode-app/runtime?id=3054')
    expect(Modal.confirm).not.toHaveBeenCalled()
    expect(log).not.toContain('gone:3054/vouchers')

    // 去登录页会卸掉整个布局：照旧询问，用户不同意就留在原地
    await go('/login')
    expect(Modal.confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.fullPath).toBe('/nocode-app/runtime?id=3054')
  })

  it('does not keep a runtime page that has no tab, yet still spares it from rebuilding inside the application', async () => {
    await mount('/nocode-app/runtime?id=9999')
    const page = runtimePage()!
    await go('/nocode-app/runtime?id=9999&menu=m2')
    expect(runtimePage()).toBe(page)
    expect(definitionCalls('9999')).toBe(1)

    await go('/system/user')
    expect(log).toContain('gone:9999/vouchers')
    expect(log).toContain('gone:9999/flows')
  })
})
