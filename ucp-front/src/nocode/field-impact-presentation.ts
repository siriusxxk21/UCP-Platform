import type { ConversionImpact, FieldConversion, ObjectOperationPreview } from '@/types/nocode/data-center'
import { conversionClearsColumn } from './field-conversion'

type Impact = ConversionImpact | ObjectOperationPreview['impacts'][number]

/** 只合并完全相同的影响；相同提示若来源、位置、处理入口或阻断级别不同，必须分别保留。 */
export function fieldImpactKey(impact: Impact): string {
  return JSON.stringify([
    impact.sourceKind,
    impact.sourceId,
    impact.sourceName,
    impact.fieldId,
    impact.location.trim(),
    impact.message.trim(),
    impact.route,
    impact.blocking,
    'code' in impact ? impact.code : null,
    'resolution' in impact ? impact.resolution.trim() : null
  ])
}

export function distinctFieldImpacts<T extends Impact>(impacts: readonly T[]): T[] {
  const seen = new Set<string>()
  return impacts.filter(impact => {
    const key = fieldImpactKey(impact)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** 结论只组织计划给出的事实，不改变清空权限和发布校验。 */
export function fieldConversionConclusion(item: FieldConversion) {
  const clear = conversionClearsColumn(item)
  const blocked =
    item.impacts.some(impact => impact.blocking) ||
    ((item.failedRows ?? 0) > 0 && (!clear || !item.clearAllowed)) ||
    (clear && item.affectedRows > 0 && !item.clearAllowed)
  if (blocked)
    return {
      type: 'error' as const,
      title: '发布前需处理此列',
      description: item.failedRows
        ? `${item.failedRows} 条记录不符合目标要求。请修正数据或配置后重新检查。`
        : item.impacts.some(impact => impact.blocking)
          ? '请按下方位置处理影响后重新检查，清空旧值不能绕过这些限制。'
          : '当前检查未允许清空此列，请查看发布检查中的具体原因。'
    }
  if (clear && item.affectedRows > 0)
    return {
      type: 'warning' as const,
      title: '发布时清空本列',
      description: `发布时清空本列全部 ${item.affectedRows} 个旧值，整条记录和其他列保留。`
    }
  if (item.action === 'KEEP_COLUMN')
    return {
      type: 'success' as const,
      title: '保留现有列值',
      description: '仅调整字段约束或默认配置；默认值只对新记录生效。'
    }
  return {
    type: 'success' as const,
    title: item.affectedRows > 0 ? `保留并转换 ${item.affectedRows} 个旧值` : '本列没有旧值',
    description: item.affectedRows > 0 ? '按下方转换规则处理，不清空本列。' : '无需清空数据。'
  }
}
