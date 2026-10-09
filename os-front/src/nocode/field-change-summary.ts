import type { FieldOptions, PublishedFieldBaseline } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { conversionTypeLabel } from './field-conversion'

export interface FieldConfigurationChange {
  label: string
  before: string
  after: string
}

/** 名称用于展示，编码才是选择值的稳定身份；顺序和名称调整也展示给设计者。 */
function optionsLabel(options: FieldOptions['options']): string {
  return options?.length
    ? options.map(item => `${item.label}（${item.code}${item.disabled ? '，停用' : ''}）`).join('、')
    : '无选项'
}

function selectionLabel(selection: FieldOptions['selection']): string {
  if (!selection || selection.kind === 'LOCAL_OPTIONS') return '当前字段自定义选项'
  if (selection.kind === 'SYSTEM_DICTIONARY') return `平台公共字典：${selection.dictionaryType || '待选择'}`
  return selection.kind === 'DIRECTORY' ? '系统目录' : selection.kind
}

/** 基于已发布配置展示本次净变化；无发布基线时使用打开面板前的草稿。 */
export function fieldConfigurationChanges(
  source: ObjectField,
  before: FieldOptions,
  target: ObjectField,
  after: FieldOptions,
  baseline?: PublishedFieldBaseline,
  sourceTargetId?: string | null,
  targetId?: string | null
): FieldConfigurationChange[] {
  const original = baseline ?? source
  const oldOptions = baseline ?? before
  const changes: FieldConfigurationChange[] = []
  const add = (
    label: string,
    oldValue: unknown,
    newValue: unknown,
    format = (value: unknown) => String(value ?? '未设置')
  ) => {
    if (JSON.stringify(oldValue ?? null) !== JSON.stringify(newValue ?? null))
      changes.push({ label, before: format(oldValue), after: format(newValue) })
  }
  add('字段类型', original.type, target.type, value => conversionTypeLabel(String(value), String(value)))
  add('文本长度', original.length, target.length)
  add('总位数', original.precision, target.precision)
  add('小数位数', original.scale, target.scale)
  add('必填', !!original.required, !!target.required, value => (value ? '必填' : '允许为空'))
  add('唯一', !!original.unique, !!target.unique, value => (value ? '值不能重复' : '允许重复'))
  add('最小值', oldOptions.minimum, after.minimum)
  add('最大值', oldOptions.maximum, after.maximum)
  add('格式规则', oldOptions.pattern, after.pattern)
  add('数据来源', oldOptions.selection, after.selection, value => selectionLabel(value as FieldOptions['selection']))
  add('自定义选项', oldOptions.options ?? [], after.options ?? [], value =>
    optionsLabel(value as FieldOptions['options'])
  )
  // 引用身份来自对象关系，不能用物理字段类型判断关系是否仍存在。
  add('关联对象', baseline ? baseline.targetObjectId : sourceTargetId, targetId)
  if ((oldOptions.defaultValue ?? null) !== (after.defaultValue ?? null))
    changes.push({
      label: '默认值（仅新记录）',
      before: oldOptions.defaultValue == null ? '未设置' : '已设置',
      after: after.defaultValue == null ? '未设置' : '使用新的默认值'
    })
  return changes
}
