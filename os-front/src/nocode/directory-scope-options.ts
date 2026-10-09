import { selectionInScope } from './selection'
import type { SelectionSource } from '@/types/nocode/selection'

export interface DirectoryScopeOption {
  value: string
  label: string
  parentValue?: string | null
  organizationType?: number
  disabled?: boolean
}
/** 默认值候选复用字段范围：先展开父子范围，再按组织类型过滤，不扩大运行时选择范围。 */
export function directoryDefaultOptions(options: DirectoryScopeOption[], source?: SelectionSource | null) {
  const scoped = selectionInScope(
    options.map(item => ({
      ...item,
      code: null,
      parentValue: item.parentValue || null,
      path: item.label,
      disabled: !!item.disabled,
      unavailable: false
    })),
    source?.rootIds,
    source?.includeDescendants
  )
  const types = source?.organizationTypes || []
  return scoped.filter(item => !item.disabled && (!types.length || types.includes(item.organizationType!)))
}
