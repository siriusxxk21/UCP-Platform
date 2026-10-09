import type { DocumentPolicy } from '@/types/nocode/document-policy'
import type { FieldOptions, SaveDesign } from '@/types/nocode/data-center'

type Lifecycle = NonNullable<DocumentPolicy['lifecycle']>
type Options = FieldOptions['options']

/** 同一状态字段按稳定编码增量同步；已删除选项保留到用户处理动作和初始状态。 */
export function synchronizeLifecycle(lifecycle: Lifecycle, options: Options): Lifecycle {
  const known = new Map(lifecycle.states.map(state => [state.code, state]))
  const codes = new Set(options.map(option => option.code))
  const states = [
    ...options.map(option => {
      const previous = known.get(option.code)
      return previous
        ? { ...previous, name: option.label }
        : { code: option.code, name: option.label, lockedFields: [], lockedDetails: [], allowDelete: true }
    }),
    ...lifecycle.states.filter(state => !codes.has(state.code))
  ]
  return JSON.stringify(states) === JSON.stringify(lifecycle.states) ? lifecycle : { ...lifecycle, states }
}

export function lifecycleRemovalImpact(lifecycle: Lifecycle, options: Options) {
  const codes = new Set(options.map(option => option.code))
  const removed = lifecycle.states.filter(state => !codes.has(state.code))
  const removedCodes = new Set(removed.map(state => state.code))
  const actions = lifecycle.actions.filter(
    action => removedCodes.has(action.toState) || action.fromStates.some(code => removedCodes.has(code))
  )
  return { removed, actions, initial: removedCodes.has(lifecycle.initialState) }
}

/** 不自动删除或改写状态动作；所有依赖修正后才允许显式移除失效状态。 */
export function removeRetiredLifecycleStates(lifecycle: Lifecycle, options: Options): Lifecycle {
  const impact = lifecycleRemovalImpact(lifecycle, options)
  if (impact.initial || impact.actions.length) throw new Error('请先调整失效状态对应的初始状态和动作')
  const removed = new Set(impact.removed.map(state => state.code))
  return { ...lifecycle, states: lifecycle.states.filter(state => !removed.has(state.code)) }
}

/** 保存入口也同步状态，覆盖尚未打开整单规则页签的字段编辑。 */
export function synchronizeDesignLifecycle(design: SaveDesign): string | null {
  const policy = design.settings.documentPolicy
  if (!policy?.lifecycle) return null
  const field = design.draft.fields.find(f => (f.id || f.key) === policy.lifecycle!.fieldId)
  if (!field) return '受控状态字段已移除，请先调整整单规则'
  const options = design.fieldOptions[field.key]?.options || []
  policy.lifecycle = synchronizeLifecycle(policy.lifecycle, options)
  if (lifecycleRemovalImpact(policy.lifecycle, options).removed.length)
    return '状态选项已移除，请在整单规则中处理受影响动作和初始状态，再移除失效状态配置'
  return null
}
