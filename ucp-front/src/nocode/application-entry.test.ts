import { describe, expect, it } from 'vitest'
import { applicationEntries, applicationEntryPayload, platformEntryDirectories } from './application-entry'
import type { Menu } from '@/types/system/menu'
import { MenuStatus, MenuType } from '@/types/system/menu'

const parent: Menu = {
  id: '9007199254740993',
  parentId: '0',
  name: '经营管理',
  path: '/business',
  menuType: MenuType.DIRECTORY,
  status: MenuStatus.ENABLED,
  sort: 1
}
const entry: Menu = {
  id: '21',
  parentId: parent.id,
  name: '订单',
  path: '/nocode-app/runtime?id=9007199254740993',
  component: 'nocode/application/runtime',
  menuType: MenuType.MENU,
  status: MenuStatus.ENABLED,
  sort: 1
}

describe('application platform entry', () => {
  it('keeps bigint application and parent ids exact and preserves base permission', () => {
    const payload = applicationEntryPayload(
      '9007199254740993',
      { parentId: parent.id, name: ' 订单应用 ', sort: 3, icon: '', status: MenuStatus.ENABLED, visible: true },
      { ...entry, permission: 'business:order:query' }
    )
    expect(payload.path).toBe(entry.path)
    expect(payload.parentId).toBe(parent.id)
    expect(payload.permission).toBe('business:order:query')
    expect(payload.name).toBe('订单应用')
  })
  it('does not take ownership of another application, component or record deep link', () => {
    const menus = [
      {
        ...parent,
        children: [
          entry,
          { ...entry, id: '22', path: '/nocode-app/runtime?id=9007199254740992' },
          { ...entry, id: '23', path: entry.path + '&recordId=1' },
          { ...entry, id: '24', component: 'other/index' }
        ]
      }
    ]
    expect(applicationEntries(menus, '9007199254740993').map(item => item.id)).toEqual(['21'])
  })
  it('only offers enabled visible first-level directories supported by the base sidebar', () => {
    expect(
      platformEntryDirectories([
        parent,
        entry,
        { ...parent, id: 'hidden', visible: false },
        { ...parent, id: 'off', status: MenuStatus.DISABLED },
        { ...parent, id: 'nested', parentId: parent.id }
      ]).map(item => item.id)
    ).toEqual([parent.id])
  })
})
