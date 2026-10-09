import { describe, expect, it } from 'vitest'
import matrixSource from '../../../ucp-server/ucp-nocode/ucp-nocode-tools/src/test/resources/contract-baseline/field-rule-matrix.json?raw'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { parseFormula } from './formula-builder'
import {
  AUTO_UPDATE_TARGET_TYPES,
  LINKAGE_READ_ONLY_DEFAULT,
  MULTI_ROW_DEFAULT,
  MULTI_ROW_MODES,
  RECORD_KEY,
  ROUNDING_DEFAULT,
  VALUELESS_OPERATORS,
  VALUE_SOURCE_MODES,
  autoUpdateBlocker,
  buildLinkage,
  cleanFieldRules,
  conditionError,
  conditionsError,
  defaultFormulaError,
  defaultValueMode,
  emptyValueError,
  emptyValueKind,
  fieldRuleTags,
  fieldRulesError,
  formFieldCompatible,
  formFieldGroups,
  formulaFieldGroups,
  isLinkageIncomplete,
  isObjectFieldOptionsIncomplete,
  isReferenceIncomplete,
  isValuelessOperator,
  linkageAnchor,
  linkageAutoUpdate,
  linkageReadOnly,
  linkageValueCompatible,
  multiRowModesFor,
  normalizeValueSource,
  objectOptionGroups,
  operatorLabel,
  operatorsFor,
  roundingCode,
  roundingOf,
  roundingOptions,
  valueSourceModeLabels,
  valueSourceModesFor
} from './field-rules'

interface MatrixCase {
  type: string
  relation: string | null
  block: string
  modes: string[]
}
const matrix = JSON.parse(matrixSource) as { cases: MatrixCase[] }
const field = (type: string, id: string | null = type, name = type): ObjectField => ({
  ...newField(0, name),
  key: id ?? 'new-' + type,
  id,
  code: type.toLowerCase(),
  type: type as ObjectField['type']
})
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
const linkage = (patch: Partial<NonNullable<FieldRules['linkage']>> = {}): NonNullable<FieldRules['linkage']> => ({
  sourceObjectId: 'src',
  conditions: [],
  valueFieldId: 'value',
  multiRow: null,
  readOnly: false,
  ...patch
})
const relation = (kind: string, patch: Partial<ObjectRelation> = {}): ObjectRelation => ({
  id: 'rel',
  code: 'rel',
  name: '关系',
  kind: kind as ObjectRelation['kind'],
  targetObjectId: 'target',
  fieldId: 'ref',
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT',
  ...patch
})

// 期望表位于 ucp-nocode-tools 的 contract-baseline，后端 FieldRuleMatrixTest 读同一份。
describe('能力矩阵与后端共用同一份 JSON 期望表', () => {
  it('每条期望逐项一致', () => {
    expect(matrix.cases.length).toBeGreaterThan(0)
    for (const item of matrix.cases) {
      const actual = valueSourceModesFor(item.type, item.relation ? { kind: item.relation } : null)
      expect({ type: item.type, relation: item.relation, ...actual }).toEqual(item)
    }
  })
  it('期望表覆盖全部字段类型，且只使用约定的块与档位', () => {
    const covered = new Set(matrix.cases.filter(item => item.relation === null).map(item => item.type))
    expect([...covered].sort()).toEqual(Object.values(FieldType).sort())
    for (const item of matrix.cases) {
      expect(['OPTIONS', 'DEFAULT', 'NONE']).toContain(item.block)
      for (const mode of item.modes) expect(VALUE_SOURCE_MODES).toContain(mode)
    }
  })
  it('选项类只有选项块，其它字段只有默认值块，两块从不同时出现', () => {
    for (const type of [FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.REGION, FieldType.CASCADE])
      expect(valueSourceModesFor(type).block).toBe('OPTIONS')
    for (const type of [FieldType.TEXT, FieldType.MONEY, FieldType.DATE, FieldType.USER, FieldType.ATTACHMENT]) {
      const result = valueSourceModesFor(type)
      expect(result.block).toBe('DEFAULT')
      expect(result.modes).not.toContain('OBJECT_FIELD_OPTIONS')
    }
    expect(valueSourceModesFor(FieldType.SELECT).modes).not.toContain('FORMULA')
  })
})

describe('B34 留空即没有默认值', () => {
  it('没有「启用默认值」档位，空白默认值归一为 null', () => {
    expect(Object.values(valueSourceModeLabels).join()).not.toContain('启用')
    const next = normalizeValueSource(FieldType.TEXT, null, options({ defaultValue: '   ' }), 'CUSTOM')
    expect(next.defaultValue).toBeNull()
    expect(next.rules).toBeNull()
    expect(defaultValueMode(next)).toBe('CUSTOM')
  })
})

describe('B35 同一时刻只落一档，换档清掉残值', () => {
  const dirty = () =>
    options({ defaultValue: '备注', rules: { linkage: linkage(), defaultFormula: 'a || b', dependsOn: ['x'] } })
  it('默认值块三档互斥', () => {
    const custom = normalizeValueSource(FieldType.TEXT, null, dirty(), 'CUSTOM')
    expect(custom).toMatchObject({ defaultValue: '备注', rules: null })
    const linked = normalizeValueSource(FieldType.TEXT, null, dirty(), 'LINKAGE')
    expect(linked.defaultValue).toBeNull()
    expect(linked.rules).toEqual({ linkage: linkage() })
    const formula = normalizeValueSource(FieldType.TEXT, null, dirty(), 'FORMULA')
    expect(formula.defaultValue).toBeNull()
    expect(formula.rules).toEqual({ defaultFormula: 'a || b' })
  })
  it('选项类永远不保存默认值与公式，数据联动叠加在候选来源上', () => {
    const next = normalizeValueSource(FieldType.SELECT, null, dirty(), 'CUSTOM')
    expect(next.defaultValue).toBeNull()
    expect(next.rules).toEqual({ linkage: linkage() })
    const saved = cleanFieldRules(FieldType.MULTI_SELECT, null, options({ defaultValue: '["a"]' }))
    expect(saved.defaultValue).toBeNull()
  })
  it('不开放的档位落回自定义，计算类整块清空', () => {
    const date = normalizeValueSource(FieldType.DATE, null, dirty(), 'FORMULA')
    expect(date.rules).toBeNull()
    expect(normalizeValueSource(FieldType.FORMULA, null, dirty(), 'LINKAGE').rules).toBeNull()
    expect(normalizeValueSource(FieldType.IMAGE, null, dirty(), 'LINKAGE').rules).toBeNull()
  })
  it('组织目录换成数据联动时清掉「当前用户所属组织」等默认方式', () => {
    const org = options({
      defaultValue: 'org-1',
      selection: {
        kind: 'DIRECTORY',
        directory: FieldType.ORGANIZATION,
        dictionaryType: null,
        rootIds: [],
        includeDescendants: false,
        organizationTypes: [],
        defaultMode: 'FIXED'
      }
    })
    const next = normalizeValueSource(FieldType.ORGANIZATION, null, org, 'LINKAGE')
    expect(next.defaultValue).toBeNull()
    expect(next.selection?.defaultMode).toBe('NONE')
    expect(org.selection?.defaultMode).toBe('FIXED')
  })
  it('关系字段保留引用规则，离开挑取值后不残留来源对象', () => {
    const reference = { labelFieldId: 'name', filter: [] }
    const next = normalizeValueSource(
      FieldType.REFERENCE,
      relation(RelationType.REFERENCE),
      options({ defaultValue: 'x', rules: { reference, defaultFormula: 'a' } }),
      'CUSTOM'
    )
    expect(next).toMatchObject({ defaultValue: null, rules: { reference } })
    const local = normalizeValueSource(
      FieldType.SELECT,
      null,
      options({
        selection: {
          kind: 'LOCAL_OPTIONS',
          directory: null,
          dictionaryType: null,
          rootIds: [],
          includeDescendants: false,
          organizationTypes: [],
          defaultMode: 'NONE',
          sourceObjectId: 'x',
          sourceFieldId: 'y'
        }
      }),
      'CUSTOM'
    )
    expect(local.selection).not.toHaveProperty('sourceObjectId')
  })
})

describe('取整方式（15.3）', () => {
  it('三档顺序固定，缺省向下取整且存 null，每档附正负数例子', () => {
    expect(roundingOptions.map(item => [item.value, item.label])).toEqual([
      ['HALF_UP', '四舍五入'],
      ['FLOOR', '向下取整'],
      ['DOWN', '去掉小数']
    ])
    expect(roundingOptions.map(item => item.example)).toEqual([
      '1.5 → 2；-1.5 → -2',
      '1.5 → 1；-1.5 → -2',
      '1.5 → 1；-1.5 → -1'
    ])
    expect(ROUNDING_DEFAULT).toBe('FLOOR')
    expect(roundingOf(null)).toBe('FLOOR')
    expect(roundingCode('FLOOR')).toBeNull()
    expect(roundingCode('HALF_UP')).toBe('HALF_UP')
  })
  it('金额字段换档保留取整方式，只有自定义时保存才清掉', () => {
    const money = options({ rules: { linkage: linkage(), rounding: 'DOWN' } })
    const formula = normalizeValueSource(
      FieldType.MONEY,
      null,
      { ...money, rules: { ...money.rules, defaultFormula: 'a' } },
      'FORMULA'
    )
    expect(formula.rules).toEqual({ defaultFormula: 'a', rounding: 'DOWN' })
    expect(cleanFieldRules(FieldType.MONEY, null, money).rules).toEqual({ linkage: linkage(), rounding: 'DOWN' })
    expect(cleanFieldRules(FieldType.MONEY, null, options({ rules: { rounding: 'DOWN' } }))).not.toHaveProperty('rules')
  })
  it('非金额字段的取整方式被清掉（包括换类型之后）', () => {
    const decimal = options({ rules: { linkage: linkage(), rounding: 'HALF_UP' } })
    expect(normalizeValueSource(FieldType.DECIMAL, null, decimal, 'LINKAGE').rules).toEqual({ linkage: linkage() })
    expect(cleanFieldRules(FieldType.INTEGER, null, decimal).rules?.rounding).toBeUndefined()
    expect(cleanFieldRules(FieldType.TEXT, null, decimal).rules).toEqual({ linkage: linkage() })
  })
})

describe('多行匹配', () => {
  it('只有四档，没有「去重取值」，缺省拼接成一行', () => {
    expect([...MULTI_ROW_MODES]).toEqual(['CONCAT', 'FIRST', 'SUM', 'ERROR'])
    expect(MULTI_ROW_DEFAULT).toBe('CONCAT')
  })
  it('求和只对数值来源列出，多选目标不列拼接', () => {
    expect(multiRowModesFor(field(FieldType.TEXT), null, FieldType.TEXT)).toEqual(['CONCAT', 'FIRST', 'ERROR'])
    expect(multiRowModesFor(field(FieldType.DECIMAL), null, FieldType.MONEY)).toContain('SUM')
    expect(multiRowModesFor(field(FieldType.FORMULA), options({ resultType: 'INTEGER' }), FieldType.INTEGER)).toContain(
      'SUM'
    )
    expect(multiRowModesFor(field(FieldType.FORMULA), options({ resultType: 'TEXT' }), FieldType.TEXT)).not.toContain(
      'SUM'
    )
    expect(multiRowModesFor(null, null, FieldType.MONEY)).not.toContain('SUM')
    expect(multiRowModesFor(field(FieldType.MULTI_SELECT), null, FieldType.MULTI_SELECT)).toEqual(['FIRST', 'ERROR'])
  })
})

describe('算子词表（7.2）', () => {
  // 2026-10-01：可作条件的字段都有「为空 / 不为空」；选项、关联、目录、布尔与文本另有「不等于」（与后端 FieldRuleMatrixTest 同表）。
  it('按字段类型给出固定词表', () => {
    for (const type of [FieldType.TEXT, FieldType.TEXTAREA, FieldType.AUTO_NUMBER])
      expect(operatorsFor(field(type))).toEqual(['eq', 'neq', 'like', 'notLike', 'isNull', 'notNull'])
    for (const type of [FieldType.DATE, FieldType.DATETIME, FieldType.TIME])
      expect(operatorsFor(field(type))).toEqual(['lt', 'gt', 'between', 'isNull', 'notNull'])
    for (const type of [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT])
      expect(operatorsFor(field(type))).toEqual(['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'isNull', 'notNull'])
    for (const type of [
      FieldType.SELECT,
      FieldType.REFERENCE,
      FieldType.USER,
      FieldType.DEPARTMENT,
      FieldType.ORGANIZATION,
      FieldType.POST,
      FieldType.USER_GROUP,
      FieldType.BOOLEAN
    ])
      expect(operatorsFor(field(type))).toEqual(['eq', 'neq', 'isNull', 'notNull'])
    expect(operatorsFor(field(FieldType.MULTI_SELECT))).toEqual(['containsAny', 'isNull', 'notNull'])
    expect(operatorsFor(RECORD_KEY)).toEqual(['eq'])
  })
  it('「为空 / 不为空」有中文名，且只有这两个比较方式不带值', () => {
    expect(operatorLabel('isNull')).toBe('为空')
    expect(operatorLabel('notNull')).toBe('不为空')
    expect(operatorLabel('neq', FieldType.SELECT)).toBe('不等于')
    expect(VALUELESS_OPERATORS).toEqual(['isNull', 'notNull'])
    for (const operator of [
      'eq',
      'neq',
      'like',
      'notLike',
      'gt',
      'gte',
      'lt',
      'lte',
      'between',
      'containsAny',
      '',
      null
    ])
      expect(isValuelessOperator(operator)).toBe(false)
    expect(isValuelessOperator('isNull')).toBe(true)
    expect(isValuelessOperator('notNull')).toBe(true)
  })
  it('不可筛的类型与 LIVE 计算字段不给算子', () => {
    for (const type of [
      FieldType.URL,
      FieldType.UUID,
      FieldType.SUMMARY,
      FieldType.IMAGE,
      FieldType.ATTACHMENT,
      FieldType.RICH_TEXT,
      FieldType.REGION,
      FieldType.CASCADE
    ])
      expect(operatorsFor(field(type))).toEqual([])
    const live = options({
      resultType: 'DECIMAL',
      calculation: {
        mode: 'LOOKUP',
        updateMode: 'LIVE',
        targetObjectId: null,
        relationId: null,
        targetField: null,
        aggregate: 'SUM',
        logic: 'AND',
        conditions: [],
        excludeCurrent: false
      }
    })
    expect(operatorsFor(field(FieldType.FORMULA), live)).toEqual([])
    expect(operatorsFor(field(FieldType.FORMULA), options({ resultType: 'INTEGER' }))).toContain('gte')
  })
})

describe('完整性判断', () => {
  const condition = (patch: Partial<RuleCondition>): RuleCondition => ({
    fieldId: 'f',
    operator: 'eq',
    valueSource: 'CONSTANT',
    value: 'A',
    ...patch
  })
  it('逐条点名缺什么', () => {
    expect(conditionError(condition({}), 0)).toBeNull()
    expect(conditionError(condition({ fieldId: '' }), 0)).toContain('来源字段')
    expect(conditionError(condition({ operator: '' }), 1)).toContain('第 2 条')
    expect(conditionError(condition({ value: '  ' }), 0)).toContain('固定值')
    expect(conditionError(condition({ valueSource: 'FORM_FIELD', formFieldId: null }), 0)).toContain('当前字段')
    expect(conditionError(condition({ valueSource: 'FORM_FIELD', formFieldId: 'x' }), 0)).toBeNull()
    expect(conditionError(condition({ operator: 'between', value: ['1', ''] }), 0)).toContain('起止')
    expect(conditionError(condition({ operator: 'between', value: ['1', '2'] }), 0)).toBeNull()
    expect(conditionError(condition({ fieldId: RECORD_KEY, operator: 'like' }), 0)).toContain('等于')
    // 为空 / 不为空不要求值；不等于与等于一样必须有值；按记录匹配仍只能用等于。
    for (const operator of ['isNull', 'notNull']) {
      expect(conditionError(condition({ operator, value: null }), 0)).toBeNull()
      expect(conditionError({ fieldId: 'f', operator, valueSource: 'CONSTANT' }, 0)).toBeNull()
      expect(conditionError(condition({ fieldId: RECORD_KEY, operator, value: null }), 0)).toContain('等于')
    }
    expect(conditionError(condition({ operator: 'neq', value: null }), 0)).toContain('固定值')
    expect(conditionError(condition({ operator: 'neq', value: 'A' }), 0)).toBeNull()
    expect(conditionError(condition({ operator: 'neq', valueSource: 'FORM_FIELD', formFieldId: null }), 0)).toContain(
      '当前字段'
    )
    expect(conditionError(condition({ fieldId: RECORD_KEY, operator: 'neq' }), 0)).toContain('等于')
    expect(conditionsError(Array.from({ length: 21 }, () => condition({})))).toContain('20')
  })
  it('联动、引用筛选、挑取值的配置不完整判据', () => {
    expect(isLinkageIncomplete(null)).toBe(false)
    expect(isLinkageIncomplete(linkage())).toBe(false)
    expect(isLinkageIncomplete(linkage({ valueFieldId: '' }))).toBe(true)
    expect(isLinkageIncomplete(linkage({ conditions: [condition({ value: null })] }))).toBe(true)
    expect(isReferenceIncomplete({ labelFieldId: 'x', filter: [] })).toBe(false)
    expect(isReferenceIncomplete({ filter: [condition({ fieldId: '' })] })).toBe(true)
    const pick = {
      kind: 'OBJECT_FIELD_OPTIONS' as const,
      directory: null,
      dictionaryType: null,
      rootIds: [],
      includeDescendants: false,
      organizationTypes: [],
      defaultMode: 'NONE' as const,
      sourceObjectId: 'x',
      sourceFieldId: null
    }
    expect(isObjectFieldOptionsIncomplete(pick)).toBe(true)
    expect(isObjectFieldOptionsIncomplete({ ...pick, sourceFieldId: 'y' })).toBe(false)
    expect(fieldRulesError(options({ selection: pick }))).toContain('挑取值')
    expect(fieldRulesError(options({ rules: { linkage: linkage({ sourceObjectId: '' }) } }))).toContain('数据联动')
    expect(fieldRulesError(options({ rules: { linkage: linkage() } }))).toBeNull()
  })
})

describe('当前字段与取值字段的候选', () => {
  it('明细字段分「本行 · / 主表 ·」两组，只列已保存的非计算字段且不含自身', () => {
    const row = [
      field(FieldType.TEXT, 'r1', '分类'),
      field(FieldType.TEXT, 'r2', '备注'),
      field(FieldType.FORMULA, 'r3', '合计'),
      field(FieldType.TEXT, null, '新字段')
    ]
    const groups = formFieldGroups({
      fields: row,
      selfKey: 'r2',
      master: { fields: [field(FieldType.TEXT, 'm1', '公司')] }
    })
    expect(groups.map(group => group.label)).toEqual(['本行', '主表'])
    expect(groups[0]!.options.map(item => item.label)).toEqual(['本行 · 分类'])
    expect(groups[1]!.options.map(item => [item.value, item.label])).toEqual([['m1', '主表 · 公司']])
    const main = formFieldGroups({ fields: row, selfKey: 'r2' })
    expect(main.map(group => group.label)).toEqual(['当前字段'])
  })
  it('条件两侧类型兼容；引用两侧须指向同一对象', () => {
    const [choice] = formFieldGroups({
      fields: [field(FieldType.REFERENCE, 'company', '公司')],
      relations: [relation(RelationType.REFERENCE, { fieldId: 'company', targetObjectId: 'org' })],
      selfKey: 'self'
    })[0]!.options
    expect(formFieldCompatible({ type: FieldType.REFERENCE, referenceTarget: 'org' }, choice!)).toBe(true)
    expect(formFieldCompatible({ type: FieldType.REFERENCE, referenceTarget: 'bank' }, choice!)).toBe(false)
    expect(formFieldCompatible({ type: FieldType.TEXT }, choice!)).toBe(false)
    const [number] = formFieldGroups({ fields: [field(FieldType.INTEGER, 'n')], selfKey: 'self' })[0]!.options
    expect(formFieldCompatible({ type: FieldType.MONEY }, number!)).toBe(true)
    expect(formFieldCompatible({ type: FieldType.DATE }, number!)).toBe(false)
  })
  it('联动取值字段按目标类型过滤', () => {
    const target = (type: string, patch: Partial<FieldOptions> = {}) => ({
      field: field(type),
      options: options(patch)
    })
    const source = (type: string, patch: Partial<FieldOptions> = {}) => ({
      field: field(type, 'src-' + type),
      options: options(patch)
    })
    expect(linkageValueCompatible(target(FieldType.MONEY), source(FieldType.DECIMAL), 'x')).toBe(true)
    expect(linkageValueCompatible(target(FieldType.MONEY), source(FieldType.TEXT), 'x')).toBe(false)
    expect(linkageValueCompatible(target(FieldType.TEXT), source(FieldType.TEXT), 'x')).toBe(true)
    expect(linkageValueCompatible(target(FieldType.RICH_TEXT), source(FieldType.TEXTAREA), 'x')).toBe(true)
    const picked = target(FieldType.SELECT, {
      selection: {
        kind: 'OBJECT_FIELD_OPTIONS',
        directory: null,
        dictionaryType: null,
        rootIds: [],
        includeDescendants: false,
        organizationTypes: [],
        defaultMode: 'NONE',
        sourceObjectId: 'x',
        sourceFieldId: 'src-SELECT'
      }
    })
    expect(linkageValueCompatible(picked, source(FieldType.SELECT), 'x')).toBe(true)
    expect(linkageValueCompatible(picked, source(FieldType.SELECT), 'other')).toBe(false)
    expect(
      linkageValueCompatible(
        { ...target(FieldType.INTEGER), referenceTarget: 'org' },
        { ...source(FieldType.REFERENCE), referenceTarget: 'org' },
        'x'
      )
    ).toBe(true)
  })
  it('文本目标的联动来源：单行文本收文本、自动编号、链接、文本公式；多行文本另收多行文本', () => {
    const target = (type: string) => ({ field: field(type), options: options() })
    const source = (type: string, patch: Partial<FieldOptions> = {}) => ({
      field: field(type, 'src-' + type),
      options: options(patch)
    })
    const text = { resultType: FieldType.TEXT },
      decimal = { resultType: FieldType.DECIMAL }
    // 行：来源；列：目标 单行文本 / 多行文本 / 链接。
    const cases: [ReturnType<typeof source>, boolean, boolean, boolean][] = [
      [source(FieldType.TEXT), true, true, false],
      [source(FieldType.AUTO_NUMBER), true, true, false],
      [source(FieldType.URL), true, true, true],
      [source(FieldType.FORMULA, text), true, true, false],
      [source(FieldType.TEXTAREA), false, true, false],
      [source(FieldType.RICH_TEXT), false, false, false],
      [source(FieldType.FORMULA, decimal), false, false, false],
      [source(FieldType.SUMMARY, decimal), false, false, false],
      [source(FieldType.INTEGER), false, false, false],
      [source(FieldType.SELECT), false, false, false],
      [source(FieldType.DATE), false, false, false],
      [source(FieldType.UUID), false, false, false]
    ]
    for (const [from, single, multi, link] of cases) {
      const label = `${from.field.type}/${from.options.resultType ?? ''}`
      expect(linkageValueCompatible(target(FieldType.TEXT), from, 'x'), `单行文本 ← ${label}`).toBe(single)
      expect(linkageValueCompatible(target(FieldType.TEXTAREA), from, 'x'), `多行文本 ← ${label}`).toBe(multi)
      expect(linkageValueCompatible(target(FieldType.URL), from, 'x'), `链接 ← ${label}`).toBe(link)
    }
    // 关联字段即便底层类型是文本也不算文本来源；数值目标不收自动编号。
    expect(
      linkageValueCompatible(target(FieldType.TEXT), { ...source(FieldType.TEXT), referenceTarget: 'org' }, 'x')
    ).toBe(false)
    expect(linkageValueCompatible(target(FieldType.INTEGER), source(FieldType.AUTO_NUMBER), 'x')).toBe(false)
  })
  it('选项目标认可反方向：来源字段的挑取值正好指向当前字段', () => {
    const picked = (objectId: string, fieldId: string): Partial<FieldOptions> => ({
      selection: {
        kind: 'OBJECT_FIELD_OPTIONS',
        directory: null,
        dictionaryType: null,
        rootIds: [],
        includeDescendants: false,
        organizationTypes: [],
        defaultMode: 'NONE',
        sourceObjectId: objectId,
        sourceFieldId: fieldId
      }
    })
    const status = { field: field(FieldType.SELECT, 'status'), options: options(), objectId: 'flow' }
    const source = (type: string, patch: Partial<FieldOptions> = {}) => ({
      field: field(type, 'voucher-status'),
      options: options(patch)
    })
    expect(linkageValueCompatible(status, source(FieldType.SELECT, picked('flow', 'status')), 'voucher')).toBe(true)
    // 挑的是别的字段、别的对象，或单选多选不一致：不算相符。
    expect(linkageValueCompatible(status, source(FieldType.SELECT, picked('flow', 'kind')), 'voucher')).toBe(false)
    expect(linkageValueCompatible(status, source(FieldType.SELECT, picked('other', 'status')), 'voucher')).toBe(false)
    expect(linkageValueCompatible(status, source(FieldType.MULTI_SELECT, picked('flow', 'status')), 'voucher')).toBe(
      false
    )
    // 当前对象或当前字段还没保存（没有 ID）时无从比对。
    expect(
      linkageValueCompatible({ ...status, objectId: null }, source(FieldType.SELECT, picked('flow', 'status')), 'x')
    ).toBe(false)
    expect(
      linkageValueCompatible(
        { ...status, field: field(FieldType.SELECT, null) },
        source(FieldType.SELECT, picked('flow', 'status')),
        'x'
      )
    ).toBe(false)
    // 互不相干的两个局部选项字段仍不相符。
    expect(linkageValueCompatible(status, source(FieldType.SELECT), 'voucher')).toBe(false)
    const tags = { field: field(FieldType.MULTI_SELECT, 'tags'), options: options(), objectId: 'flow' }
    expect(linkageValueCompatible(tags, source(FieldType.MULTI_SELECT, picked('flow', 'tags')), 'voucher')).toBe(true)
  })
  it('来源对象按分类分组', () => {
    expect(
      objectOptionGroups([
        { value: 'a', label: 'A', category: '财务' },
        { value: 'b', label: 'B', category: '人事' },
        { value: 'c', label: 'C', category: '财务' }
      ]).map(group => [group.label, group.options.map(item => item.value)])
    ).toEqual([
      ['财务', ['a', 'c']],
      ['人事', ['b']]
    ])
  })
})

describe('公式默认值', () => {
  it('金额目标写 round 被拒，按 AST 判断不做子串匹配', () => {
    expect(defaultFormulaError(parseFormula('round(price * qty, 0)'), { roundForbidden: true })).toContain('round')
    expect(defaultFormulaError(parseFormula('round(price * qty, 0)'), {})).toBeNull()
    expect(defaultFormulaError(parseFormula('around * 2'), { roundForbidden: true })).toBeNull()
  })
  it('明细公式引用主表与本行重名编码被拒，重名编码单独列出', () => {
    const row = [field(FieldType.DECIMAL, 'r1', '金额')]
    const master = [field(FieldType.DECIMAL, 'm1', '金额'), field(FieldType.INTEGER, 'm2', '数量')]
    master[1]!.code = 'qty'
    const groups = formulaFieldGroups(row, master)
    expect(groups.conflictCodes).toEqual(['decimal'])
    expect(groups.groups?.map(group => group.label)).toEqual(['本行', '主表'])
    expect(groups.fields.map(item => item.code)).toEqual(['decimal', 'qty'])
    expect(defaultFormulaError(parseFormula('decimal * qty'), { conflictCodes: groups.conflictCodes })).toContain(
      '重名'
    )
    expect(formulaFieldGroups(row, null).groups).toBeUndefined()
  })
})

describe('数据联动只读（业务方 2026-09-29 裁定）', () => {
  it('新配默认开启；存量 null 按开启，只有显式 false 才关闭', () => {
    expect(LINKAGE_READ_ONLY_DEFAULT).toBe(true)
    expect(linkageReadOnly(null)).toBe(true)
    expect(linkageReadOnly(linkage({ readOnly: null }))).toBe(true)
    expect(linkageReadOnly(linkage({ readOnly: true }))).toBe(true)
    expect(linkageReadOnly(linkage({ readOnly: false }))).toBe(false)
  })
})

describe('字段表小标签', () => {
  it('只显示「联动」「公式默认」「引用筛选」', () => {
    expect(fieldRuleTags(options())).toEqual([])
    expect(
      fieldRuleTags(
        options({
          rules: {
            linkage: linkage(),
            defaultFormula: 'a',
            reference: {
              labelFieldId: null,
              filter: [{ fieldId: 'x', operator: 'eq', valueSource: 'CONSTANT', value: 1 }]
            }
          }
        })
      )
    ).toEqual(['联动', '公式默认', '引用筛选'])
    expect(fieldRuleTags(options({ rules: { reference: { labelFieldId: 'name', filter: [] } } }))).toEqual([])
  })
})

// 第一期契约 9.1、9.2：存量联动没有 autoUpdate 键即关；只有 true 才算开，关闭时不写这个键。
describe('来源变化时自动更新（第一期）', () => {
  const currentRecord: RuleCondition = { fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }
  const recordKey: RuleCondition = { fieldId: RECORD_KEY, operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'g' }
  const constant: RuleCondition = { fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'A' }

  it('只有 autoUpdate 为 true 才算开：没有这个键、null、false 都是关（存量联动不动）', () => {
    expect(linkageAutoUpdate(null)).toBe(false)
    expect(linkageAutoUpdate(undefined)).toBe(false)
    expect(linkageAutoUpdate({})).toBe(false)
    expect(linkageAutoUpdate({ autoUpdate: null })).toBe(false)
    expect(linkageAutoUpdate({ autoUpdate: false })).toBe(false)
    expect(linkageAutoUpdate({ autoUpdate: true })).toBe(true)
  })

  it('锚点只有两种：「来源关联字段 等于 当前记录」优先，其次「按记录匹配 等于 当前字段」', () => {
    expect(linkageAnchor([])).toBeNull()
    expect(linkageAnchor(null)).toBeNull()
    expect(linkageAnchor([constant])).toBeNull()
    expect(linkageAnchor([constant, currentRecord])).toBe('CURRENT_RECORD')
    expect(linkageAnchor([recordKey])).toBe('RECORD_KEY')
    expect(linkageAnchor([recordKey, currentRecord])).toBe('CURRENT_RECORD')
    // 按记录匹配还没选当前字段，或值来源不是当前字段，都不算锚点。
    expect(linkageAnchor([{ ...recordKey, formFieldId: null }])).toBeNull()
    expect(linkageAnchor([{ fieldId: RECORD_KEY, operator: 'eq', valueSource: 'CONSTANT', value: '1' }])).toBeNull()
  })

  const base = {
    readOnly: true,
    detail: false,
    fieldType: FieldType.SELECT as string,
    relation: false,
    sourceObjectId: 'src',
    objectId: 'self',
    conditions: [currentRecord],
    valueField: { type: FieldType.SELECT as string }
  }
  it('可开条件逐条给出原因，全部满足才返回 null', () => {
    expect(autoUpdateBlocker(base)).toBeNull()
    expect(autoUpdateBlocker({ ...base, conditions: [recordKey] })).toBeNull()
    expect(autoUpdateBlocker({ ...base, readOnly: false })).toBe('可手改的字段不会跟随来源变化')
    expect(autoUpdateBlocker({ ...base, detail: true })).toBe('明细字段暂不支持')
    expect(autoUpdateBlocker({ ...base, fieldType: FieldType.RICH_TEXT })).toBe('这种类型的字段暂不支持')
    expect(autoUpdateBlocker({ ...base, fieldType: FieldType.USER })).toBe('这种类型的字段暂不支持')
    expect(autoUpdateBlocker({ ...base, relation: true })).toBe('这种类型的字段暂不支持')
    expect(autoUpdateBlocker({ ...base, sourceObjectId: 'self' })).toBe('来源对象是本对象时暂不支持')
    expect(autoUpdateBlocker({ ...base, conditions: [constant] })).toBe(
      '需要一条按记录匹配的条件：「来源对象的关联字段 等于 当前记录」或「按记录匹配 等于 当前字段」'
    )
    expect(autoUpdateBlocker({ ...base, valueField: { type: FieldType.FORMULA } })).toBe(
      '带入的来源字段是计算字段时暂不支持'
    )
    expect(autoUpdateBlocker({ ...base, valueField: { type: FieldType.SUMMARY } })).toBe(
      '带入的来源字段是计算字段时暂不支持'
    )
    // 还没选取值字段不算原因（由「请选择要带入的来源字段」另行拦住）。
    expect(autoUpdateBlocker({ ...base, valueField: null })).toBeNull()
    // 对象还没保存（没有对象 ID）时不会误判成「来源是本对象」。
    expect(autoUpdateBlocker({ ...base, objectId: null })).toBeNull()
  })
  it('多个原因同时存在时按「只读 → 明细 → 类型 → 本对象 → 锚点 → 计算字段」的顺序报第一条', () => {
    const all = {
      ...base,
      readOnly: false,
      detail: true,
      fieldType: FieldType.RICH_TEXT as string,
      sourceObjectId: 'self',
      conditions: [],
      valueField: { type: FieldType.FORMULA as string }
    }
    expect(autoUpdateBlocker(all)).toBe('可手改的字段不会跟随来源变化')
    expect(autoUpdateBlocker({ ...all, readOnly: true })).toBe('明细字段暂不支持')
    expect(autoUpdateBlocker({ ...all, readOnly: true, detail: false })).toBe('这种类型的字段暂不支持')
    expect(autoUpdateBlocker({ ...all, readOnly: true, detail: false, fieldType: FieldType.TEXT })).toBe(
      '来源对象是本对象时暂不支持'
    )
    expect(
      autoUpdateBlocker({ ...all, readOnly: true, detail: false, fieldType: FieldType.TEXT, sourceObjectId: 'src' })
    ).toContain('需要一条按记录匹配的条件')
  })
  it('一期目标类型白名单：文本、数值、金额、百分比、布尔、日期时间、单选、多选', () => {
    expect([...AUTO_UPDATE_TARGET_TYPES].sort()).toEqual(
      [
        FieldType.TEXT,
        FieldType.TEXTAREA,
        FieldType.INTEGER,
        FieldType.DECIMAL,
        FieldType.MONEY,
        FieldType.PERCENT,
        FieldType.BOOLEAN,
        FieldType.DATE,
        FieldType.DATETIME,
        FieldType.TIME,
        FieldType.SELECT,
        FieldType.MULTI_SELECT
      ].sort()
    )
  })

  it('「没有匹配记录时填入」的控件种类随目标类型；不支持的类型与关联字段为 null', () => {
    expect(emptyValueKind(FieldType.TEXT)).toBe('text')
    expect(emptyValueKind(FieldType.TEXTAREA)).toBe('text')
    expect(emptyValueKind(FieldType.SELECT)).toBe('choice')
    expect(emptyValueKind(FieldType.INTEGER)).toBe('integer')
    expect(emptyValueKind(FieldType.DECIMAL)).toBe('decimal')
    expect(emptyValueKind(FieldType.PERCENT)).toBe('decimal')
    expect(emptyValueKind(FieldType.MONEY)).toBe('money')
    expect(emptyValueKind(FieldType.BOOLEAN)).toBe('boolean')
    for (const type of [
      FieldType.MULTI_SELECT,
      FieldType.DATE,
      FieldType.DATETIME,
      FieldType.TIME,
      FieldType.RICH_TEXT,
      FieldType.USER,
      FieldType.REGION,
      FieldType.URL,
      FieldType.ATTACHMENT
    ])
      expect(emptyValueKind(type)).toBeNull()
    expect(emptyValueKind(FieldType.SELECT, { kind: RelationType.REFERENCE })).toBeNull()
    // 单选配成系统目录（值是目录 ID，不是选项编码）时没有选项可选。
    expect(emptyValueKind(FieldType.SELECT, null, options({ selection: { kind: 'DIRECTORY' } as never }))).toBeNull()
  })
  it('字面量前端先拦：金额只收整数，数值按字段精度，文本不超长，布尔只有是否', () => {
    const money = { ...field(FieldType.MONEY, 'm', '金额'), precision: 15, scale: 0 }
    expect(emptyValueError('money', '0', money)).toBeNull()
    expect(emptyValueError('money', '-1200', money)).toBeNull()
    expect(emptyValueError('money', '12.5', money)).toBe('金额按日元整数保存，请填写整数')
    expect(emptyValueError('money', '12.0', money)).toBe('金额按日元整数保存，请填写整数')
    expect(emptyValueError('money', 'abc', money)).toBe('请填写数字')
    const integer = field(FieldType.INTEGER, 'i', '次数')
    expect(emptyValueError('integer', '12', integer)).toBeNull()
    expect(emptyValueError('integer', '1.5', integer)).toBe('请填写整数')
    expect(emptyValueError('integer', '1e2', integer)).toBe('请填写整数')
    const decimal = { ...field(FieldType.DECIMAL, 'd', '比例'), precision: 6, scale: 2 }
    expect(emptyValueError('decimal', '12.34', decimal)).toBeNull()
    expect(emptyValueError('decimal', '１２', decimal)).toBe('请填写数字')
    expect(emptyValueError('decimal', '1e2', decimal)).toBe('请填写数字')
    expect(emptyValueError('decimal', '1.234', decimal)).toContain('最多保留 2 位小数')
    const text = { ...field(FieldType.TEXT, 't', '备注'), length: 4 }
    expect(emptyValueError('text', '未登记', text)).toBeNull()
    expect(emptyValueError('text', '未登记凭证', text)).toBe('不能超过 4 个字')
    // 没设长度的文本字段按 4000 校验。
    const unlimited = { ...field(FieldType.TEXTAREA, 'a', '说明'), length: null }
    expect(emptyValueError('text', 'x'.repeat(4000), unlimited)).toBeNull()
    expect(emptyValueError('text', 'x'.repeat(4001), unlimited)).toBe('不能超过 4000 个字')
    expect(emptyValueError('boolean', 'true', field(FieldType.BOOLEAN, 'b', '是否'))).toBeNull()
    expect(emptyValueError('boolean', '是', field(FieldType.BOOLEAN, 'b', '是否'))).toBe('请选择「是」或「否」')
    // 不填 = 没有匹配时写空，不是错误。
    for (const kind of ['text', 'choice', 'integer', 'decimal', 'money', 'boolean'] as const)
      expect(emptyValueError(kind, '', money)).toBeNull()
  })

  it('金额只收整数不靠字段的小数位设置：字段定义了 2 位小数或没定义小数位时，带小数同样被拦', () => {
    for (const scale of [2, null]) {
      const money = { ...field(FieldType.MONEY, 'm', '金额'), precision: 15, scale }
      expect(emptyValueError('money', '12.5', money)).toBe('金额按日元整数保存，请填写整数')
      expect(emptyValueError('money', '12', money)).toBeNull()
    }
  })

  const draft = {
    sourceObjectId: 'src',
    conditions: [currentRecord],
    valueFieldId: 'status',
    multiRow: 'FIRST' as const,
    readOnly: true
  }
  it('写出联动：开才写 autoUpdate: true；关闭时不带这个键（不写 false）；emptyValue 为空不带键', () => {
    const on = buildLinkage({ ...draft, autoUpdate: true, emptyValue: 'wdj' })
    expect(on).toEqual({ ...draft, autoUpdate: true, emptyValue: 'wdj' })
    const off = buildLinkage({ ...draft, autoUpdate: false, emptyValue: 'wdj' })
    expect(off).toEqual(draft)
    expect(off).not.toHaveProperty('autoUpdate')
    expect(off).not.toHaveProperty('emptyValue')
    const blank = buildLinkage({ ...draft, autoUpdate: true, emptyValue: '   ' })
    expect(blank).toEqual({ ...draft, autoUpdate: true })
    expect(blank).not.toHaveProperty('emptyValue')
    // 序列化结果里没有这两个键：存量规则原样确定后逐字不变。
    expect(JSON.stringify(off)).toBe(JSON.stringify(draft))
  })

  it('条件校验认得「当前记录」：只许「等于」、不要求值，不能配在「按记录匹配」上，至多一条', () => {
    expect(conditionError(currentRecord, 0)).toBeNull()
    expect(conditionError({ ...currentRecord, operator: 'neq' }, 0)).toBe('第 1 条条件：「当前记录」只能用「等于」')
    expect(conditionError({ fieldId: RECORD_KEY, operator: 'eq', valueSource: 'CURRENT_RECORD' }, 1)).toBe(
      '第 2 条条件：「按记录匹配」不能选「当前记录」，请改选来源对象上的关联字段'
    )
    expect(conditionsError([currentRecord, constant])).toBeNull()
    expect(conditionsError([currentRecord, { ...currentRecord, fieldId: 'flow2' }])).toBe(
      '「当前记录」的条件只能有一条'
    )
    expect(isLinkageIncomplete({ ...draft, autoUpdate: true })).toBe(false)
  })
})
