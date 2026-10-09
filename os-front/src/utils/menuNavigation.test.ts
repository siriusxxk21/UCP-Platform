import { describe, expect, it } from 'vitest'
import type { Menu } from '@/types'
import {
  childMenus,
  findBestMatchingMenu,
  findTopMenuFromCache,
  resolveMenuNavPath,
  resolveTopMenuNavPath,
} from './menuNavigation'

function menu(id: string, path: string, parentId: string): Menu {
  return { id, name: id, path, parentId, sort: 0 }
}

describe('menu navigation resolver', () => {
  it('prefers a page entry over its legacy application entry regardless of menu order', () => {
    const menus = [
      menu('business', '/business', '0'),
      menu('app', '/nocode-app/runtime?id=41', 'business'),
      menu('orders', '/nocode-app/runtime?id=41&page=orders', 'business'),
      menu('suppliers', '/nocode-app/runtime?id=41&page=suppliers', 'business'),
      menu('other-app', '/nocode-app/runtime?id=42&page=orders', 'business'),
    ]
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=41&page=orders')?.id).toBe('orders')
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=41&page=suppliers&recordId=9')?.id).toBe('suppliers')
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=42&page=orders')?.id).toBe('other-app')
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=41')?.id).toBe('app')
  })

  it('keeps applications using the same runtime route under their configured parent', () => {
    const menus = [
      menu('business', '/business', '0'),
      menu('sales', '/sales', '0'),
      menu('orders', '/nocode-app/runtime?id=41', 'business'),
      menu('customers', '/nocode-app/runtime?id=205', 'sales'),
    ]
    expect(findTopMenuFromCache(menus, '/nocode-app/runtime?id=205&menu=customer_list')?.id).toBe('sales')
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=41&menu=order_list')?.id).toBe('orders')
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=489')).toBeUndefined()
    expect(findBestMatchingMenu(menus, '/nocode-app/runtime?id=41&id=205')).toBeUndefined()
  })
  it('keeps adjacent bigint menu parents separate', () => {
    const dataId = '9007199254740992'
    const appId = '9007199254740993'
    const menus = [
      menu(dataId, '/nocode', '0'),
      menu(appId, '/nocode-app', '0'),
      menu('11', '/nocode/object', dataId),
      menu('12', '/nocode-app/application', appId),
    ]
    expect(childMenus(menus, appId).map(item => item.id)).toEqual(['12'])
    expect(findTopMenuFromCache(menus, '/nocode-app/application')?.id).toBe(appId)
  })
  it('resolves the top menu from cached parent relations instead of the URL prefix', () => {
    const menus = [menu('bpm', '/bpm', '0'), menu('task', '/task', '0'), menu('todo', '/bpm/task/todo', 'task')]

    expect(findTopMenuFromCache(menus, '/bpm/task/todo')?.id).toBe('task')
  })

  it('follows all cached parent levels to the top menu', () => {
    const menus = [
      menu('task', '/task', '0'),
      menu('task-group', '/task/group', 'task'),
      menu('todo', '/bpm/task/todo', 'task-group'),
    ]

    expect(findTopMenuFromCache(menus, '/bpm/task/todo')?.id).toBe('task')
  })

  it('prefers an exact side menu over an earlier prefix menu', () => {
    const menus = [menu('task-group', '/bpm/task', 'task'), menu('todo', '/bpm/task/todo', 'task')]

    expect(findBestMatchingMenu(menus, '/bpm/task/todo')?.id).toBe('todo')
  })
})

/** 目录：没有组件；页面：配了组件。sort 与后端一致，按同级顺序给。 */
function directory(id: string, path: string, parentId = '0', sort = 0): Menu {
  return { id, name: id, path, parentId, sort }
}

function page(id: string, path: string, parentId = '0', sort = 0): Menu {
  return { ...directory(id, path, parentId, sort), component: `${id}/index` }
}

describe('top menu click target', () => {
  it('goes to the first clickable child by sort when the top menu has children', () => {
    const menus = [
      directory('system', '/system'),
      page('role', '/system/role', 'system', 2),
      page('user', '/system/user', 'system', 1),
    ]

    expect(resolveTopMenuNavPath(menus, 'system')).toBe('/system/user')
  })

  it('prefers a clickable child even when the top menu itself is a page', () => {
    const menus = [page('building', '/nocode-app/runtime?id=3057'), page('ledger', '/building/ledger', 'building')]

    expect(resolveTopMenuNavPath(menus, 'building')).toBe('/building/ledger')
  })

  it('opens a top-level page menu itself and keeps the query that identifies the mounted application', () => {
    const menus = [
      directory('business', '/busitest'),
      page('finance', '/nocode-app/runtime?id=3001', 'business'),
      page('building', '/nocode-app/runtime?id=3057'),
      page('homestay', '/nocode-app/runtime?id=3056'),
    ]

    expect(resolveTopMenuNavPath(menus, 'building')).toBe('/nocode-app/runtime?id=3057')
    expect(resolveTopMenuNavPath(menus, 'homestay')).toBe('/nocode-app/runtime?id=3056')
  })

  it('strips path placeholders from a top-level page menu the same way side menus do', () => {
    const menus = [page('images', '/repository/images/:id?'), page('home', 'dashboard')]

    expect(resolveTopMenuNavPath(menus, 'images')).toBe('/repository/images')
    expect(resolveTopMenuNavPath(menus, 'home')).toBe('/dashboard')
  })

  it('stays put when the top menu has neither a clickable child nor a page of its own', () => {
    const menus = [directory('empty', '/empty'), directory('titleOnly', ''), page('noPath', '')]

    expect(resolveTopMenuNavPath(menus, 'empty')).toBeUndefined()
    expect(resolveTopMenuNavPath(menus, 'titleOnly')).toBeUndefined()
    expect(resolveTopMenuNavPath(menus, 'noPath')).toBeUndefined()
    expect(resolveTopMenuNavPath(menus, 'missing')).toBeUndefined()
  })

  it('does not treat section titles as clickable children', () => {
    const sectionsOf = (parentId: string): Menu[] => [
      { ...directory(`${parentId}-a`, '/section/a', parentId, 0), menuType: 0 },
      { ...directory(`${parentId}-b`, '/section/b', parentId, 1), menuType: 0 },
    ]
    const menus = [
      directory('grouped', '/grouped'),
      ...sectionsOf('grouped'),
      page('building', '/nocode-app/runtime?id=3057'),
      ...sectionsOf('building'),
      directory('mixed', '/mixed'),
      { ...directory('mixed-title', '/mixed/title', 'mixed', 0), menuType: 0 },
      page('mixed-list', '/mixed/list', 'mixed', 1),
    ]

    expect(resolveTopMenuNavPath(menus, 'grouped')).toBeUndefined()
    expect(resolveTopMenuNavPath(menus, 'building')).toBe('/nocode-app/runtime?id=3057')
    expect(resolveTopMenuNavPath(menus, 'mixed')).toBe('/mixed/list')
  })

  it('keeps adjacent bigint top menus apart when resolving the click target', () => {
    const directoryId = '9007199254740992'
    const pageId = '9007199254740993'
    const menus = [
      directory(directoryId, '/nocode'),
      page('object', '/nocode/object', directoryId),
      page(pageId, '/nocode-app/runtime?id=3057'),
    ]

    expect(resolveTopMenuNavPath(menus, directoryId)).toBe('/nocode/object')
    expect(resolveTopMenuNavPath(menus, pageId)).toBe('/nocode-app/runtime?id=3057')
  })
})

describe('menu nav path', () => {
  it('removes path placeholders but keeps the configured query', () => {
    expect(resolveMenuNavPath('/repository/images/:id?')).toBe('/repository/images')
    expect(resolveMenuNavPath('/project/:projectId/board?tab=managed')).toBe('/project/board?tab=managed')
    expect(resolveMenuNavPath('/nocode-app/runtime?id=3057')).toBe('/nocode-app/runtime?id=3057')
    expect(resolveMenuNavPath('dashboard')).toBe('/dashboard')
    expect(resolveMenuNavPath('')).toBe('/')
  })
})
