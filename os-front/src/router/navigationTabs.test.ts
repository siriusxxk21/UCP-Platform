import { describe, expect, it } from 'vitest'
import type { Menu } from '@/types'
import { resolveFixedNavigationTab, resolveNavigationTab } from './navigationTabs'

function systemMenu(id: string, name: string, path: string, sort = 0): Menu {
  return { id, name, path, component: `${id}/index`, parentId: '1', sort }
}

function route(
  path: string,
  query: Record<string, string> = {},
  meta: Record<string, unknown> = {},
): Parameters<typeof resolveNavigationTab>[0] {
  const queryText = new URLSearchParams(query).toString()
  return {
    path,
    fullPath: queryText ? `${path}?${queryText}` : path,
    query,
    matched: [{ meta }],
  } as Parameters<typeof resolveNavigationTab>[0]
}

describe('navigation tab resolver', () => {
  it('keeps independent report menus separate and falls back to the report list only without an entry', () => {
    const path = '/nocode/report-center/dashboard-view'
    const parent = '/nocode/report-center/dashboards'
    const menus = [
      systemMenu('reports', '仪表板', parent),
      systemMenu('sales', '销售分析', `${path}?id=308`),
      systemMenu('cash', '资金分析', `${path}?id=309`),
    ]
    const meta = { hidden: true, tabParentPath: parent }
    expect(resolveNavigationTab(route(path, { id: '308' }, meta), menus)?.key).toBe('system:sales')
    expect(resolveNavigationTab(route(path, { id: '309' }, meta), menus)?.key).toBe('system:cash')
    expect(resolveNavigationTab(route(path, { id: '310' }, meta), menus)?.key).toBe('system:reports')
  })

  it('gives each published application page its own tab ahead of the legacy application entry', () => {
    const menus = [
      systemMenu('app', '采购应用', '/nocode-app/runtime?id=41'),
      systemMenu('home', '采购工作台', '/nocode-app/runtime?id=41&page=home'),
      systemMenu('orders', '采购订单', '/nocode-app/runtime?id=41&page=orders'),
    ]
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '41', page: 'home' }), menus)?.key).toBe(
      'system:home',
    )
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '41', page: 'orders' }), menus)?.key).toBe(
      'system:orders',
    )
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '41', menu: 'old' }), menus)?.key).toBe('system:app')
  })

  it('keeps one tab per mounted application and does not borrow another application entry', () => {
    const menus = [
      systemMenu('orders', '订单应用', '/nocode-app/runtime?id=41'),
      systemMenu('customers', '客户应用', '/nocode-app/runtime?id=205'),
    ]
    const first = resolveNavigationTab(route('/nocode-app/runtime', { id: '41', menu: 'orders' }), menus)
    const second = resolveNavigationTab(route('/nocode-app/runtime', { id: '205', menu: 'customers' }), menus)
    expect(first?.key).toBe('system:orders')
    expect(second?.key).toBe('system:customers')
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '41', menu: 'dashboard' }), menus)?.key).toBe(
      first?.key,
    )
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '489' }), menus)).toBeNull()
  })
  const systemMenus = [
    systemMenu('dashboard', '首页', '/dashboard', 2),
    systemMenu('user', '用户管理', '/system/user', 3),
    systemMenu('enabled-user', '启用用户', '/system/user?status=1', 4),
  ]

  it('uses dashboard as the fixed landing tab', () => {
    expect(resolveFixedNavigationTab(systemMenus)?.key).toBe('system:dashboard')
    expect(resolveFixedNavigationTab(systemMenus)?.closable).toBe(false)
  })

  it('uses a synthetic dashboard tab when dashboard is not returned by the backend', () => {
    const menus = [systemMenu('second', '第二菜单', '/second', 2), systemMenu('first', '第一菜单', '/first', 1)]

    expect(resolveFixedNavigationTab(menus)?.key).toBe('system:dashboard')
    expect(resolveFixedNavigationTab(menus)?.title).toBe('首页')
  })

  it('distinguishes system menus by configured query', () => {
    expect(resolveNavigationTab(route('/system/user'), systemMenus)?.key).toBe('system:user')

    expect(resolveNavigationTab(route('/system/user', { status: '1' }), systemMenus)?.key).toBe('system:enabled-user')
  })

  it('gives a top-level page menu its own closable tab named after the menu', () => {
    const menus: Menu[] = [
      { ...systemMenu('building', '建筑', '/nocode-app/runtime?id=3057'), parentId: '0' },
      { ...systemMenu('homestay', '民宿', '/nocode-app/runtime?id=3056'), parentId: '0' },
    ]

    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '3057', menu: 'ledger' }), menus)).toMatchObject({
      key: 'system:building',
      title: '建筑',
      menuPath: '/nocode-app/runtime?id=3057',
      fullPath: '/nocode-app/runtime?id=3057&menu=ledger',
      closable: true,
    })
    expect(resolveNavigationTab(route('/nocode-app/runtime', { id: '3056' }), menus)?.title).toBe('民宿')
    expect(resolveFixedNavigationTab(menus)?.key).toBe('system:dashboard')
  })
})
