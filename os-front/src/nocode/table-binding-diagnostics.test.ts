import { describe, expect, it } from 'vitest'
import type { AdoptionPreflight, DatabaseColumn, TableBinding } from '@/types/nocode/data-center'
import { tableBindingDiagnostics } from './table-binding-diagnostics'

function column(name: string, nativeType: string, nullable = false): DatabaseColumn {
  return {
    name,
    nativeType,
    nullable,
    ordinal: 1,
    defaultExpression: null,
    identityKind: '',
    generatedKind: '',
    primaryKeyPosition: name === 'id' ? 1 : 0,
    comment: null
  }
}
function fixture() {
  const binding: TableBinding = {
    source: 'ADOPTED',
    schemaName: 'public',
    keyColumn: 'id',
    parentColumn: null,
    structureMode: 'RETAIN',
    readOnly: false,
    repairBaseFields: false,
    fingerprint: 'test'
  }
  const preflight: AdoptionPreflight = {
    schemaName: 'public',
    tableName: 'existing_table',
    fingerprint: 'test',
    allowed: true,
    readOnly: false,
    checks: [],
    titleColumns: ['id'],
    structure: {
      relation: { schema: 'public', name: 'existing_table', kind: 'TABLE', comment: null },
      columns: [
        column('id', 'bigint'),
        column('creator', 'character varying(64)', true),
        column('create_time', 'timestamp(6) without time zone'),
        column('updater', 'character varying(64)', true),
        column('update_time', 'timestamp without time zone'),
        column('deleted', 'smallint')
      ],
      constraints: [],
      indexes: [],
      triggers: [],
      statistics: {
        estimatedRows: 0,
        tableBytes: 0,
        indexBytes: 0,
        totalBytes: 0,
        rowSecurity: false,
        forceRowSecurity: false,
        canSelect: true,
        canWrite: true
      }
    }
  }
  return { binding, preflight }
}

describe('已有表绑定的能力说明', () => {
  it('当前表已纳管不是绑定编辑的通用阻断，读取权限缺失仍须显示', () => {
    const { binding, preflight } = fixture()
    preflight.allowed = false
    preflight.checks = [{ code: 'CLAIMED', message: '该表已关联数据对象或纳管草稿', blocking: true }]
    expect(tableBindingDiagnostics(binding, preflight)).toMatchObject({ blockers: [], claimed: true })
    preflight.checks.push({ code: 'SELECT_PERMISSION', message: '当前数据库账号没有读取权限', blocking: true })
    expect(tableBindingDiagnostics(binding, preflight).blockers).toEqual(['当前数据库账号没有读取权限'])
  })

  it('触发器只阻止接管结构，保留原结构仍可只读映射，给出具体触发器名', () => {
    const { binding, preflight } = fixture()
    preflight.structure.triggers = [{ name: 'audit_changes', enabled: 'O', definition: '' }]
    const retained = tableBindingDiagnostics(binding, preflight)
    expect(retained.blockers).toEqual([])
    expect(retained.readOnlyReasons).toEqual(['原表存在触发器：audit_changes'])
    binding.structureMode = 'MANAGED'
    expect(tableBindingDiagnostics(binding, preflight).blockers[0]).toContain('请选择“保留原结构”')
  })

  it('不支持写入的字段不额外禁止结构管理，仍明确指出该列只读', () => {
    const { binding, preflight } = fixture()
    binding.structureMode = 'MANAGED'
    preflight.checks = [
      {
        code: 'COLUMN_READ_ONLY',
        message: 'location 使用暂不支持写入的类型或生成方式，保留原结构并只读',
        blocking: false
      }
    ]
    expect(tableBindingDiagnostics(binding, preflight)).toMatchObject({
      blockers: [],
      structuralRestrictions: [],
      effectiveReadOnly: true
    })
    expect(tableBindingDiagnostics(binding, preflight).readOnlyReasons[0]).toContain('location')
  })

  it('只有明确选择平台管理并补齐缺失列，才显示发布后可写；不修改当前草稿', () => {
    const { binding, preflight } = fixture()
    preflight.structure.columns = preflight.structure.columns.filter(item => item.name !== 'deleted')
    expect(tableBindingDiagnostics(binding, preflight).effectiveReadOnly).toBe(true)
    binding.structureMode = 'MANAGED'
    expect(tableBindingDiagnostics(binding, preflight).effectiveReadOnly).toBe(true)
    binding.repairBaseFields = true
    expect(tableBindingDiagnostics(binding, preflight)).toMatchObject({
      effectiveReadOnly: false,
      missingColumns: ['deleted'],
      repairActive: true
    })
    expect(binding.readOnly).toBe(false)
    binding.readOnly = true
    expect(tableBindingDiagnostics(binding, preflight).effectiveReadOnly).toBe(true)
  })

  it('已有公共列的类型或非空规范差异不能冒充缺失列自动修复', () => {
    const { binding, preflight } = fixture()
    binding.structureMode = 'MANAGED'
    binding.repairBaseFields = true
    preflight.structure.columns = preflight.structure.columns.map(item =>
      item.name === 'deleted' ? { ...item, nativeType: 'integer', nullable: true } : item
    )
    const result = tableBindingDiagnostics(binding, preflight)
    expect(result).toMatchObject({ repairPossible: false, repairActive: false, effectiveReadOnly: true })
    expect(result.mismatchedColumns).toEqual(['deleted：当前 integer，可为空；要求 smallint，不可为空'])
    expect(result.blockers[0]).toContain('请取消补齐')
    binding.repairBaseFields = false
    expect(tableBindingDiagnostics(binding, preflight).blockers).toEqual([])
  })

  it('读写权限与 RLS 分别呈现，不从估算记录数推断任何数据可删除结论', () => {
    const { binding, preflight } = fixture()
    preflight.structure.statistics.canWrite = false
    preflight.structure.statistics.rowSecurity = true
    preflight.structure.statistics.estimatedRows = 0
    const result = tableBindingDiagnostics(binding, preflight)
    expect(result.structuralRestrictions).toEqual([
      '当前数据库账号不具备该表完整的写入权限',
      '原表启用了行级安全（RLS）'
    ])
    expect(result.effectiveReadOnly).toBe(true)
    expect(result.blockers).toEqual([])
  })
})
