import type { ReportFolder } from '@/types/nocode/report-center'
export interface FolderNode {
  key: string
  value: string
  title: string
  children: FolderNode[]
}
/** 只组织服务端已按权限过滤的目录；不根据列表补造目录或资源权限。 */
export function folderTree(folders: ReportFolder[], exclude?: string): FolderNode[] {
  const nodes = new Map(
    folders.map(f => [f.id, { key: f.id, value: f.id, title: f.name, children: [] as FolderNode[] }])
  )
  const roots: FolderNode[] = []
  for (const folder of folders) {
    const node = nodes.get(folder.id)
    if (!node || folder.id === exclude) continue
    const parent = folder.parentId ? nodes.get(folder.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
export function folderPath(folders: ReportFolder[], id: string | null): string {
  const path: string[] = [],
    visited = new Set<string>()
  let current = id
  while (current && !visited.has(current)) {
    visited.add(current)
    const folder = folders.find(f => f.id === current)
    if (!folder) break
    path.unshift(folder.name)
    current = folder.parentId
  }
  return path.join(' / ') || '未分类'
}
