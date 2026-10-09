// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { message } from 'ant-design-vue'
import type { Menu } from '@/types'
import { useUserStore } from '@/stores/user'
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
    userInfo: { username: 'tester' },
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

const passThrough = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.())
})
const page = { render: () => null }

/** 「系统」是带子菜单的目录，「建筑」是自己就是页面的一级菜单，「空目录」两样都没有。 */
const menus: Menu[] = [
  { id: '1', name: '系统', path: '/system', parentId: '0', sort: 0 },
  { id: '100', name: '用户管理', path: '/system/user', component: 'system/user/index', parentId: '1', sort: 0 },
  {
    id: '3057',
    name: '建筑',
    path: '/nocode-app/runtime?id=3057',
    component: 'nocode/application/runtime',
    parentId: '0',
    sort: 1
  },
  { id: '9', name: '空目录', path: '/empty', parentId: '0', sort: 2 }
]

let app: App | undefined, host: HTMLDivElement, router: Router

/** 路由跳转要走完整条守卫链，用宏任务把排队的微任务全部放完，再等界面更新。 */
async function flush() {
  for (let index = 0; index < 3; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}

async function mount(startPath: string) {
  useUserStore().menus = menus
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/system/user/:id?', component: page },
      { path: '/nocode-app/runtime/:id?', component: page }
    ]
  })
  await router.push(startPath)
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(BasicLayout)
  app.use(router)
  for (const name of ['tooltip', 'button', 'badge', 'dropdown', 'menu', 'menu-item', 'menu-divider'])
    app.component(`a-${name}`, passThrough)
  app.mount(host)
  await flush()
}

async function clickTopMenu(name: string) {
  const item = Array.from(host.querySelectorAll<HTMLElement>('.l1-item')).find(
    element => element.querySelector('.l1-label')?.textContent === name
  )
  if (!item) throw new Error(`missing top menu ${name}`)
  item.click()
  await flush()
}

const activeTopMenu = () => host.querySelector('.l1-item.active .l1-label')?.textContent
const sidePanel = () => host.querySelector('.sidebar-l2')
const sidebarIsNarrow = () => host.querySelector('.sidebar-wrap')?.classList.contains('l2-hidden')

describe('basic layout with a top-level page menu', () => {
  beforeEach(() => vi.mocked(message.info).mockClear())
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })

  it('opens the top-level page menu itself and gives the side panel space to the content', async () => {
    await mount('/system/user')
    expect(activeTopMenu()).toBe('系统')
    expect(sidePanel()?.textContent).toContain('用户管理')
    expect(sidebarIsNarrow()).toBe(false)

    await clickTopMenu('建筑')

    expect(message.info).not.toHaveBeenCalled()
    expect(router.currentRoute.value.fullPath).toBe('/nocode-app/runtime?id=3057')
    expect(activeTopMenu()).toBe('建筑')
    expect(sidePanel()).toBeNull()
    expect(sidebarIsNarrow()).toBe(true)

    await clickTopMenu('系统')

    expect(router.currentRoute.value.fullPath).toBe('/system/user')
    expect(sidePanel()?.textContent).toContain('用户管理')
    expect(sidebarIsNarrow()).toBe(false)
  })

  it('highlights the top-level page menu when the page is entered directly by address', async () => {
    await mount('/nocode-app/runtime?id=3057&menu=ledger')

    expect(activeTopMenu()).toBe('建筑')
    expect(sidePanel()).toBeNull()
    expect(sidebarIsNarrow()).toBe(true)
  })

  it('stays on the current page with a light hint when the top menu has nothing to open', async () => {
    await mount('/system/user')

    await clickTopMenu('空目录')

    expect(router.currentRoute.value.fullPath).toBe('/system/user')
    expect(activeTopMenu()).toBe('系统')
    expect(sidePanel()).not.toBeNull()
    expect(message.info).toHaveBeenCalledTimes(1)
  })
})
