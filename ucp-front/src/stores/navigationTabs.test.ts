// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Menu } from '@/types'
import { resolveFixedNavigationTab, resolveNavigationTab } from '@/router/navigationTabs'
import { useNavigationTabsStore } from './navigationTabs'

type RouteLike = Parameters<typeof resolveNavigationTab>[0]

function runtimeRoute(query: Record<string, string>): RouteLike {
  return {
    path: '/nocode-app/runtime',
    fullPath: `/nocode-app/runtime?${new URLSearchParams(query).toString()}`,
    query,
    matched: [{ meta: {} }]
  } as RouteLike
}

/** 「建筑」是顶级菜单且自己就是页面；「用户管理」是普通的二级菜单。 */
const menus: Menu[] = [
  {
    id: '3057',
    name: '建筑',
    path: '/nocode-app/runtime?id=3057',
    component: 'nocode/application/runtime',
    parentId: '0',
    sort: 0
  },
  { id: '1', name: '系统', path: '/system', parentId: '0', sort: 1 },
  { id: '100', name: '用户管理', path: '/system/user', component: 'system/user/index', parentId: '1', sort: 0 }
]

/** 运行页带着给定查询参数打开时，路由守卫会登记的那个标签。 */
function runtimeTab(query: Record<string, string>) {
  const tab = resolveNavigationTab(runtimeRoute(query), menus)
  if (!tab) throw new Error('运行页没有解析出标签')
  return tab
}

/** 模拟刷新页面：内存状态清空，只剩 sessionStorage 里的标签。 */
function reloadedStore() {
  setActivePinia(createPinia())
  const store = useNavigationTabsStore()
  const fixedTab = resolveFixedNavigationTab(menus)
  store.initialize('7', 'tenant', fixedTab)
  store.syncSystemMenus(menus, fixedTab)
  return store
}

describe('navigation tabs of a top-level page menu', () => {
  beforeEach(() => sessionStorage.clear())

  it('keeps the tab across a reload and lets it be closed back to the landing tab', () => {
    const store = reloadedStore()
    store.register(runtimeTab({ id: '3057', menu: 'ledger' }))

    const reloaded = reloadedStore()

    expect(reloaded.tabs.map(tab => tab.title)).toEqual(['首页', '建筑'])
    expect(reloaded.activeTab).toMatchObject({
      key: 'system:3057',
      fullPath: '/nocode-app/runtime?id=3057&menu=ledger',
      closable: true
    })
    expect(reloaded.close('system:3057')).toBe('/dashboard')
    expect(reloaded.tabs.map(tab => tab.title)).toEqual(['首页'])
  })

  it('drops the tab once the menu is no longer granted', () => {
    const store = reloadedStore()
    store.register(runtimeTab({ id: '3057' }))

    const remaining = menus.filter(menu => menu.id !== '3057')
    store.syncSystemMenus(remaining, resolveFixedNavigationTab(remaining))

    expect(store.tabs.map(tab => tab.title)).toEqual(['首页'])
    expect(store.activeKey).toBe('system:dashboard')
  })
})
