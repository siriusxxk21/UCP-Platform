import type { BusinessFileSpace } from '@/types/nocode/business-file'

/** 每个入口独立读取授权空间，禁止以对象 ID 合并不同应用的可见范围。 */
export interface BusinessNavigationEntry {
  key: string
  label: string
  spaces: BusinessFileSpace[]
  loading: boolean
  error?: string
}

export interface BusinessNavigationSelection {
  entryKey: string
  objectId: string
}

export interface BusinessNavigationNode {
  key: string
  title: string
  entryKey: string
  objectId?: string
  description?: string
  selectable: boolean
  disabled?: boolean
  isLeaf?: boolean
  loading?: boolean
  error?: string
  children?: BusinessNavigationNode[]
}

export const businessNavigationKey = (entryKey: string, objectId?: string): string =>
  JSON.stringify(objectId === undefined ? [entryKey] : [entryKey, objectId])

/** 搜索覆盖已读取的全部入口，应用命中时展示其全部业务对象。 */
export function businessNavigationTree(entries: BusinessNavigationEntry[], search = ''): BusinessNavigationNode[] {
  const keyword = search.trim().toLocaleLowerCase()
  return entries.flatMap(entry => {
    const entryMatches = entry.label.toLocaleLowerCase().includes(keyword)
    const spaces = entry.spaces.filter(space => entryMatches || space.objectName.toLocaleLowerCase().includes(keyword))
    if (keyword && !entryMatches && !spaces.length) return []
    const children: BusinessNavigationNode[] = spaces.map(space => ({
      key: businessNavigationKey(entry.key, space.objectId),
      title: space.objectName,
      description: space.spaceName,
      entryKey: entry.key,
      objectId: space.objectId,
      selectable: true,
      isLeaf: true
    }))
    if (!children.length)
      children.push({
        key: `${businessNavigationKey(entry.key)}:state`,
        title: entry.loading ? '正在加载业务对象…' : entry.error ? '加载失败，可重试' : '暂无可见业务文件',
        entryKey: entry.key,
        selectable: false,
        disabled: true,
        isLeaf: true
      })
    return [
      {
        key: businessNavigationKey(entry.key),
        title: entry.label,
        entryKey: entry.key,
        selectable: false,
        loading: entry.loading,
        error: entry.error,
        children
      }
    ]
  })
}

/** 深链指定对象严格匹配；普通进入优先展示有文件的对象，空应用不遮挡其他入口。 */
export function businessNavigationSelection(
  entries: BusinessNavigationEntry[],
  preferredEntryKey?: string,
  objectId?: string
): BusinessNavigationSelection | undefined {
  if (objectId) {
    const entry = entries.find(item => item.key === preferredEntryKey)
    return entry?.spaces.some(space => space.objectId === objectId) ? { entryKey: entry.key, objectId } : undefined
  }
  const ordered = [
    ...entries.filter(item => item.key === preferredEntryKey),
    ...entries.filter(item => item.key !== preferredEntryKey)
  ]
  for (const filesOnly of [true, false])
    for (const entry of ordered) {
      const space = entry.spaces.find(item => !filesOnly || item.fileCount > 0)
      if (space) return { entryKey: entry.key, objectId: space.objectId }
    }
  return undefined
}
