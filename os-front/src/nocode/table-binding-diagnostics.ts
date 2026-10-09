import type { AdoptionPreflight, TableBinding } from '@/types/nocode/data-center'
import { StructureMode } from '@/types/nocode/enums'

/** 与 BaseDOColumns 对齐的展示说明；是否允许保存和发布始终由服务端重新判定。 */
const baseColumns = [
  { name: 'creator', type: 'varchar(64)', required: false },
  { name: 'create_time', type: 'timestamp without time zone', required: true },
  { name: 'updater', type: 'varchar(64)', required: false },
  { name: 'update_time', type: 'timestamp without time zone', required: true },
  { name: 'deleted', type: 'smallint', required: true }
]
const normalizeType = (type: string) =>
  type.replace('character varying', 'varchar').replace('timestamp(6)', 'timestamp')

/** 预检 allowed 是首次纳管准入，不能用它阻止已纳管表保存当前绑定。 */
export function tableBindingDiagnostics(binding: TableBinding, preflight: AdoptionPreflight) {
  const structure = preflight.structure
  const structuralRestrictions = [
    ...(!structure.statistics.canWrite ? ['当前数据库账号不具备该表完整的写入权限'] : []),
    ...(structure.statistics.rowSecurity ? ['原表启用了行级安全（RLS）'] : []),
    ...(structure.triggers.length ? [`原表存在触发器：${structure.triggers.map(item => item.name).join('、')}`] : [])
  ]
  const missing = baseColumns.filter(expected => !structure.columns.some(column => column.name === expected.name))
  const mismatched = baseColumns.flatMap(expected => {
    const actual = structure.columns.find(column => column.name === expected.name)
    if (!actual || (normalizeType(actual.nativeType) === expected.type && (!expected.required || !actual.nullable)))
      return []
    return [
      `${expected.name}：当前 ${actual.nativeType}${actual.nullable ? '，可为空' : '，不可为空'}；要求 ${expected.type}${expected.required ? '，不可为空' : ''}`
    ]
  })
  const unsupportedColumns = preflight.checks
    .filter(check => check.code === 'COLUMN_READ_ONLY')
    .map(check => check.message)
  const repairActive = binding.structureMode === StructureMode.MANAGED && binding.repairBaseFields
  const repairPossible = !structuralRestrictions.length && !mismatched.length
  const readOnlyReasons = [
    ...structuralRestrictions,
    ...unsupportedColumns,
    ...mismatched,
    ...(missing.length && !(repairActive && repairPossible)
      ? [`缺少公共字段：${missing.map(column => column.name).join('、')}`]
      : [])
  ]
  const blockers = preflight.checks
    .filter(check => check.blocking && check.code !== 'CLAIMED')
    .map(check => check.message)
  if (binding.structureMode === StructureMode.MANAGED && structuralRestrictions.length)
    blockers.push('当前表不能由平台管理结构变更。请选择“保留原结构”，或由原表负责人处理下面列出的限制后重新检查。')
  if (binding.repairBaseFields && !repairPossible)
    blockers.push(
      '当前不能补齐公共字段。请取消补齐；已有公共列的规范差异需由原表负责人修正，平台不会覆盖或重建这些列。'
    )
  return {
    blockers,
    structuralRestrictions,
    unsupportedColumns,
    missingColumns: missing.map(column => column.name),
    mismatchedColumns: mismatched,
    readOnlyReasons,
    repairPossible,
    repairActive: repairActive && repairPossible,
    effectiveReadOnly: binding.readOnly || readOnlyReasons.length > 0,
    claimed: preflight.checks.some(check => check.code === 'CLAIMED')
  }
}
