import { describe, expect, it, vi } from 'vitest'
import type { DirEntry, Item } from 'vuefinder'
import { LOCKED_NOTE_MENU_ID, READ_ONLY_FEATURES, lockedMenuItems } from './drive-folder-browser-menu'

type MenuApp = Parameters<Item['show']>[0]
const app = {} as MenuApp
const ids = { rename: 'rename', move: 'move', delete: 'delete' }
const NOTE = '只读：不是经由这条记录放进去的'

const entry = (name: string): DirEntry => ({
  dir: '/',
  basename: name,
  extension: 'txt',
  path: '/' + name,
  storage: '存储',
  type: 'file',
  file_size: 1,
  last_modified: null,
  mime_type: 'text/plain',
  visibility: 'private'
})
const own = entry('自己的.txt')
const foreign = entry('别人的.txt')
const isLocked = (item: DirEntry) => item.path === foreign.path

/** 组件默认菜单的替身：三项受保护的各有自己的显示条件，另有两项不相干的 */
function defaults(): Item[] {
  const one = (ctx: { items: DirEntry[] }) => ctx.items.length === 1
  const some = (ctx: { items: DirEntry[] }) => ctx.items.length >= 1
  return [
    { id: 'download', title: () => '下载', action: vi.fn(), show: (_app, ctx) => one(ctx), order: 90 },
    { id: 'rename', title: () => '改名', action: vi.fn(), show: (_app, ctx) => one(ctx), order: 100 },
    { id: 'move', title: () => '移动', action: vi.fn(), show: (_app, ctx) => some(ctx), order: 110 },
    { id: 'copy', title: () => '复制', action: vi.fn(), show: (_app, ctx) => some(ctx), order: 120 },
    { id: 'delete', title: () => '删除', action: vi.fn(), show: (_app, ctx) => some(ctx), order: 160 }
  ]
}
const ctx = (...items: DirEntry[]) => ({ items, target: items[0] ?? null, searchQuery: '' })
const shown = (items: Item[], ...selected: DirEntry[]) =>
  items.filter(item => item.show(app, ctx(...selected))).map(item => item.id)

describe('右键菜单的逐项只读包装', () => {
  it('选中项里有不能改的：改名、移动、删除不显示，换成一行说明；其余照常', () => {
    const items = lockedMenuItems(defaults(), ids, isLocked, NOTE, vi.fn())
    expect(shown(items, foreign)).toEqual(['download', 'copy', LOCKED_NOTE_MENU_ID])
  })

  it('选中项全是能改的：三项照原样显示，没有说明项', () => {
    const items = lockedMenuItems(defaults(), ids, isLocked, NOTE, vi.fn())
    expect(shown(items, own)).toEqual(['download', 'rename', 'move', 'copy', 'delete'])
  })

  it('多选里只要有一项不能改：移动、删除不显示，说明项显示', () => {
    const items = lockedMenuItems(defaults(), ids, isLocked, NOTE, vi.fn())
    expect(shown(items, own, foreign)).toEqual(['copy', LOCKED_NOTE_MENU_ID])
    expect(shown(items, own, entry('也是自己的.txt'))).toEqual(['move', 'copy', 'delete'])
  })

  it('原来就不显示的项不会因为包装而显示', () => {
    const items = lockedMenuItems(defaults(), ids, isLocked, NOTE, vi.fn())
    // 改名只在单选时出现：多选的全是能改的，也仍然没有改名
    expect(shown(items, own, entry('也是自己的.txt'))).not.toContain('rename')
    expect(shown(items)).toEqual([])
  })

  it('说明项排在下载之后、改名之前，标题是那句话，点它把这句话交给通知', () => {
    const notify = vi.fn()
    const items = lockedMenuItems(defaults(), ids, isLocked, NOTE, notify)
    const note = items.find(item => item.id === LOCKED_NOTE_MENU_ID)
    expect(note?.order).toBe(95)
    expect(note?.title({} as Parameters<Item['title']>[0])).toBe(NOTE)
    note?.action(app, [foreign])
    expect(notify).toHaveBeenCalledWith(NOTE)
  })

  it('三项以外的菜单项是同一个对象，没有被包装', () => {
    const base = defaults()
    const items = lockedMenuItems(base, ids, isLocked, NOTE, vi.fn())
    expect(items[0]).toBe(base[0])
    expect(items[3]).toBe(base[3])
    expect(items[1]).not.toBe(base[1])
  })

  it('没给说明文字：返回的就是传入的那个数组，菜单与原来逐项相同', () => {
    const base = defaults()
    for (const note of [undefined, ''] as const) {
      const items = lockedMenuItems(base, ids, isLocked, note, vi.fn())
      expect(items).toBe(base)
      expect(items.map(item => item.id)).toEqual(['download', 'rename', 'move', 'copy', 'delete'])
      // 即使选中的是不能改的，三项也照原条件显示
      expect(shown(items, foreign)).toEqual(['download', 'rename', 'move', 'copy', 'delete'])
    }
  })
})

describe('不能写的人要关掉的功能', () => {
  it('上传、新建、改名、移动、复制、删除全部关掉；查看类的不在里面', () => {
    expect(READ_ONLY_FEATURES).toEqual({
      newfolder: false,
      upload: false,
      rename: false,
      delete: false,
      move: false,
      copy: false
    })
  })
})
