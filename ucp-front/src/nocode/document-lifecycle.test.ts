import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import {
  lifecycleRemovalImpact,
  removeRetiredLifecycleStates,
  synchronizeDesignLifecycle,
  synchronizeLifecycle
} from './document-lifecycle'
import { defaultFieldOptions, newDesign } from './data-center'
import type { DocumentPolicy } from '@/types/nocode/document-policy'

const lifecycle = (): NonNullable<DocumentPolicy['lifecycle']> => ({
  fieldId: 'status',
  initialState: 'draft',
  states: [
    { code: 'draft', name: '草稿', lockedFields: ['amount'], lockedDetails: ['lines'], allowDelete: false },
    { code: 'posted', name: '登记', lockedFields: [], lockedDetails: [], allowDelete: false }
  ],
  actions: [{ code: 'post', name: '登记', fromStates: ['draft'], toState: 'posted', permission: 'UPDATE' }]
})
const options = (codes = ['draft', 'posted', 'void']) =>
  codes.map(code => ({ code, label: code === 'draft' ? '新草稿名称' : code, disabled: false }))
describe('受控状态按稳定编码增量维护', () => {
  it('增加和重命名选项保留动作、初始状态和原锁定属性，重复同步不变', () => {
    const before = reactive(lifecycle()),
      previous = JSON.stringify(before)
    const result = synchronizeLifecycle(before, options())
    expect(result.states.map(s => s.code)).toEqual(['draft', 'posted', 'void'])
    expect(result.states[0]).toMatchObject({
      name: '新草稿名称',
      lockedFields: ['amount'],
      lockedDetails: ['lines'],
      allowDelete: false
    })
    expect(result.actions).toEqual(before.actions)
    expect(result.initialState).toBe('draft')
    expect(JSON.stringify(before)).toBe(previous)
    expect(synchronizeLifecycle(result, options())).toBe(result)
  })
  it('删除选项不静默丢动作；必须先修正动作与初始状态才可移除', () => {
    const state = synchronizeLifecycle(lifecycle(), options(['posted']))
    expect(state.states.map(s => s.code)).toEqual(['posted', 'draft'])
    expect(lifecycleRemovalImpact(state, options(['posted']))).toMatchObject({
      initial: true,
      actions: [{ code: 'post' }]
    })
    expect(() => removeRetiredLifecycleStates(state, options(['posted']))).toThrow('先调整')
    state.initialState = 'posted'
    state.actions[0]!.fromStates = ['posted']
    const result = removeRetiredLifecycleStates(state, options(['posted']))
    expect(result.states.map(s => s.code)).toEqual(['posted'])
    expect(result.actions).toEqual(state.actions)
  })
  it('保存入口覆盖未打开规则页签的选项变化，失效引用明确阻断', () => {
    const design = newDesign()
    const field = design.draft.fields[0]!
    field.key = 'status'
    field.id = 'status'
    field.type = 'SELECT'
    design.settings.documentPolicy = { rules: [], lifecycle: lifecycle() }
    design.fieldOptions.status = { ...defaultFieldOptions(), options: options() }
    expect(synchronizeDesignLifecycle(design)).toBeNull()
    expect(design.settings.documentPolicy.lifecycle!.states).toHaveLength(3)
    design.fieldOptions.status.options = options(['posted', 'void'])
    expect(synchronizeDesignLifecycle(design)).toContain('受影响动作')
    expect(design.settings.documentPolicy.lifecycle!.actions).toHaveLength(1)
  })
})
