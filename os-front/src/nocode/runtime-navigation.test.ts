import { describe, expect, it } from 'vitest'
import type { ApplicationResource } from '@/types/nocode/application'
import type { Menu } from '@/types/system/menu'
import {
  applicationHome,
  applicationPagePath,
  isStandaloneApplicationPage,
  mountedApplicationPages,
  publishedNavigation
} from './runtime-navigation'

const resource = (
  id: string,
  kind: ApplicationResource['kind'],
  config: Record<string, unknown> = {}
): ApplicationResource => ({ id, kind, name: id, code: id, config })
const page = (id: string) => resource(id, 'PAGE')
const menu = (id: string, targetId: string, config: Record<string, unknown> = {}) =>
  resource(id, 'MENU', { targetId, ...config })

describe('published page navigation', () => {
  it('uses a configured hidden homepage without exposing it in the sidebar', () => {
    const resources = [
      page('orders'),
      page('home'),
      menu('orders-menu', 'orders'),
      menu('home-menu', 'home', { navigationVersion: 2, defaultHome: true, showInMenu: false })
    ]
    expect(applicationHome(resources)?.id).toBe('home')
    expect(publishedNavigation(resources).map(item => item.id)).toEqual(['orders-menu'])
  })

  it('lets explicit page settings supersede legacy duplicates while preserving their identity', () => {
    const resources = [
      page('orders'),
      page('home'),
      menu('old-orders', 'orders'),
      menu('new-orders', 'orders', { navigationVersion: 2, showInMenu: false }),
      menu('old-home', 'home')
    ]
    expect(publishedNavigation(resources).map(item => item.id)).toEqual(['old-home'])
    expect(resources.find(item => item.id === 'old-orders')?.config.targetId).toBe('orders')
  })

  it('orders visible entries and excludes unavailable or record-dependent targets', () => {
    const resources = [
      page('orders'),
      page('home'),
      resource('detail', 'PAGE', { contextObjectId: '1' }),
      menu('m1', 'orders', { navigationVersion: 2, showInMenu: true, sort: 30 }),
      menu('m2', 'home', { navigationVersion: 2, showInMenu: true, sort: 10 }),
      menu('m3', 'missing'),
      menu('m4', 'detail')
    ]
    expect(publishedNavigation(resources).map(item => item.id)).toEqual(['m2', 'm1'])
    expect(applicationHome(resources)?.id).toBe('home')
    expect(isStandaloneApplicationPage(resource('report', 'REPORT'))).toBe(false)
    expect(isStandaloneApplicationPage(resource('dashboard', 'REPORT_DASHBOARD'))).toBe(true)
  })

  it('encodes stable resource identities and never borrows another application menu', () => {
    expect(applicationPagePath('9007199254740993', 'page&1')).toBe(
      '/nocode-app/runtime?id=9007199254740993&page=page%261'
    )
    const menus = [
      { id: '1', path: '/nocode-app/runtime?id=41' },
      { id: '2', path: '/nocode-app/runtime?id=41&page=orders' },
      { id: '3', path: '/nocode-app/runtime?id=42&page=orders' },
      { id: '4', path: '/nocode-app/runtime?id=41&id=42&page=orders' }
    ] as Menu[]
    expect(mountedApplicationPages(menus, '41').map(item => item.id)).toEqual(['2'])
  })
})
