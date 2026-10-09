import type { InjectionKey, Ref } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import {
  SelectionKind,
  type SelectionSource,
  type SelectionOption,
  type SelectionPresentation
} from '@/types/nocode/selection'
export const selectionPreviewKey: InjectionKey<Ref<import('@/types/nocode/selection').SelectionPreviewContext>> =
  Symbol('selection-preview')

/** 视图固定范围同时限定列表、统计与表单候选；列出把该视图用作候选范围的表单名称。 */
export function selectionViewFormNames(resources: ApplicationResource[], viewId: string): string[] {
  const contained = (nodes: unknown): boolean =>
    Array.isArray(nodes) &&
    nodes.some(
      node =>
        !!node &&
        typeof node === 'object' &&
        ((node as { presentation?: { selection?: SelectionPresentation } }).presentation?.selection?.viewId ===
          viewId ||
          contained((node as { children?: unknown }).children))
    )
  return resources
    .filter(resource => resource.kind === ResourceKind.FORM)
    .filter(resource => {
      const config = (resource.config || {}) as { nodes?: unknown; detailNodes?: Record<string, unknown> }
      return contained(config.nodes) || Object.values(config.detailNodes || {}).some(nodes => contained(nodes))
    })
    .map(resource => resource.name)
}

export function selectionInScope<T extends SelectionOption>(
  options: T[],
  roots?: string[] | null,
  descendants = false
): T[] {
  // 已发布配置会把未设置的范围序列化为 null，与省略范围使用相同语义。
  if (!roots?.length) return options
  const allowed = new Set(roots)
  if (descendants) {
    let changed = true
    while (changed) {
      changed = false
      for (const option of options)
        if (option.parentValue && allowed.has(option.parentValue) && !allowed.has(option.value)) {
          allowed.add(option.value)
          changed = true
        }
    }
  }
  return options.filter(o => allowed.has(o.value))
}

export const selectionDirectories: FieldType[] = [
  FieldType.ORGANIZATION,
  FieldType.DEPARTMENT,
  FieldType.USER,
  FieldType.POST,
  FieldType.USER_GROUP
]
export const selectionTypes: FieldType[] = [FieldType.SELECT, FieldType.MULTI_SELECT, ...selectionDirectories]
export function selectionSource(field: ObjectField, options?: FieldOptions): SelectionSource | null {
  if (options?.selection) return options.selection
  const directory = selectionDirectories.includes(field.type)
  if (!directory && ![FieldType.SELECT, FieldType.MULTI_SELECT].some(t => t === field.type)) return null
  return {
    kind: directory ? SelectionKind.DIRECTORY : SelectionKind.LOCAL_OPTIONS,
    directory: directory ? field.type : null,
    dictionaryType: null,
    rootIds: [],
    includeDescendants: false,
    organizationTypes: [],
    defaultMode: 'NONE'
  }
}
/** 缺失的上级作为当前根节点处理，避免配置范围和检索结果产生不可选择的孤立节点。 */
export function selectionTree(options: SelectionOption[]) {
  type Tree = SelectionOption & { title: string; searchText: string; key: string; children: Tree[] }
  const map = new Map<string, Tree>(
    options.map(o => [
      o.value,
      { ...o, title: o.label, searchText: (o.path || o.label) + ' ' + (o.code || ''), key: o.value, children: [] }
    ])
  )
  const roots: Tree[] = []
  for (const item of map.values()) {
    const seen = new Set([item.value])
    let parent = map.get(item.parentValue || '')
    let cyclic = false
    while (parent) {
      if (seen.has(parent.value)) {
        cyclic = true
        break
      }
      seen.add(parent.value)
      parent = map.get(parent.parentValue || '')
    }
    const direct = map.get(item.parentValue || '')
    if (direct && !cyclic) direct.children.push(item)
    else roots.push(item)
  }
  return roots
}

export const selectionValuesKey: InjectionKey<Ref<Record<string, unknown>>> = Symbol.for(
  'ucp-platform.selection.form-values'
)
