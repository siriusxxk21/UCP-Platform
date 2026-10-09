/**
 * 文件浏览器右键菜单的「逐项只读」包装
 *
 * 组件的菜单项只有显示 / 不显示两态，没有置灰态：选中项里有不能改的，就不显示改名、移动、删除，
 * 换成一行只读说明。不依赖组件实例，便于单独验证；没给说明文字时原样返回，网盘自己的页面菜单逐项不变。
 */
import type { DirEntry, Item } from 'vuefinder'

export const LOCKED_NOTE_MENU_ID = 'drive-locked-note'

export function lockedMenuItems(
  items: Item[],
  ids: { rename: string; move: string; delete: string },
  isLocked: (entry: DirEntry) => boolean,
  note: string | undefined,
  notify: (text: string) => void
): Item[] {
  if (!note) return items
  const guarded = new Set([ids.rename, ids.move, ids.delete])
  const guard = (item: Item): Item => ({
    ...item,
    show: (app, ctx) => item.show(app, ctx) && !ctx.items.some(isLocked)
  })
  return [
    ...items.map(item => (guarded.has(item.id) ? guard(item) : item)),
    {
      id: LOCKED_NOTE_MENU_ID,
      title: () => note,
      order: 95,
      show: (_app, ctx) => ctx.items.some(isLocked),
      action: () => notify(note)
    }
  ]
}

/** 不能写的人：组件里所有会写入的功能都关掉（工具栏、右键、菜单栏、键盘、拖拽一起不出），只留查看、预览、下载、搜索 */
export const READ_ONLY_FEATURES = {
  newfolder: false,
  upload: false,
  rename: false,
  delete: false,
  move: false,
  copy: false
} as const
