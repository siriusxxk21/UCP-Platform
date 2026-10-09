import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { generateRoutesFromMenus } from './routeMap'

type RouteMenu = Parameters<typeof generateRoutesFromMenus>[0][number]

const RUNTIME = 'nocode/application/runtime'

function menu(
  id: number | string,
  name: string,
  path: string,
  parentId: number | string,
  component?: string
): RouteMenu {
  return { id, name, path, parentId, component, sort: 0 }
}

/** 与真实布局一致：应用运行页另有一条静态兜底路由，它把菜单高亮指向「应用管理」。 */
function layoutRouter() {
  const stub = { render: () => null }
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        name: 'Layout',
        component: stub,
        children: [
          {
            path: '/nocode-app/runtime',
            name: 'NocodeApplicationRuntime',
            component: stub,
            meta: { activeMenu: '/nocode-app/application' }
          }
        ]
      }
    ]
  })
}

describe('dynamic routes from menus', () => {
  it('registers a top-level page menu even when it is the only mount point of its component', () => {
    // 后端把父级 0 序列化成数字 0，本地缓存里也可能是字符串 '0'。
    for (const parentId of [0, '0']) {
      const routes = generateRoutesFromMenus([menu(3057, '建筑', '/nocode-app/runtime?id=3057', parentId, RUNTIME)])

      expect(routes.map(route => route.path)).toEqual(['/nocode-app/runtime/:id?'])
      expect(routes[0].name).toBe('建筑_3057')
      expect(routes[0].meta?.menuId).toBe(3057)
    }
  })

  it('registers an ordinary top-level page menu that has no static route to fall back on', () => {
    const routes = generateRoutesFromMenus([menu(41, '用户', '/sysuser', 0, 'system/user/index')])

    expect(routes.map(route => route.path)).toEqual(['/sysuser/:id?'])
  })

  it('still skips directories and keeps one record per path and component', () => {
    const routes = generateRoutesFromMenus([
      menu(1, '系统', '/system', 0),
      menu('2096933044259827714', '业务测试', '/busitest', 0, ''),
      menu(3001, '财务', '/nocode-app/runtime?id=3001', '2096933044259827714', RUNTIME),
      menu(3057, '建筑', '/nocode-app/runtime?id=3057', 0, RUNTIME),
      menu(3056, '民宿', '/nocode-app/runtime?id=3056', 0, RUNTIME)
    ])

    expect(routes.map(route => route.name)).toEqual(['财务_3001'])
  })

  it('lets the menu route win over the static runtime route so the top-level menu stays highlighted', () => {
    const router = layoutRouter()
    expect(router.resolve('/nocode-app/runtime?id=3057').name).toBe('NocodeApplicationRuntime')

    generateRoutesFromMenus([menu(3057, '建筑', '/nocode-app/runtime?id=3057', 0, RUNTIME)]).forEach(route =>
      router.addRoute('Layout', route)
    )
    const resolved = router.resolve('/nocode-app/runtime?id=3057')

    expect(resolved.name).toBe('建筑_3057')
    expect(resolved.meta.activeMenu).toBeUndefined()
    expect(resolved.fullPath).toBe('/nocode-app/runtime?id=3057')
    expect(resolved.query.id).toBe('3057')
  })
})
