import { describe, expect, it } from 'vitest'
import {
  changeFieldType,
  changeSelectionCount,
  clearMultilinePatterns,
  copyFieldOptions,
  designFieldsError,
  fieldBasicsError,
  fieldConfigurationError,
  fieldNumericConstraintError,
  fieldPatternError,
  fieldNamePatch,
  fieldStructureLocked
} from './field-editing'
import { defaultFieldOptions, generatedBinding, newDesign } from './data-center'
import { newField } from './object-draft'
import { systemFields } from './system-fields'
import { selectionSource } from './selection'

describe('对象字段行编辑与系统字段', () => {
  it('同一目录改变数量保留范围、组织类型和兼容默认值，不能静默挑选多项之一', () => {
    const field = { ...newField(0, '组织'), type: 'ORGANIZATION' as const }
    const options = {
      ...defaultFieldOptions(),
      defaultValue: 'org-a',
      selection: {
        ...selectionSource(field)!,
        rootIds: ['root'],
        includeDescendants: true,
        organizationTypes: [1],
        defaultMode: 'FIXED' as const
      }
    }
    expect(changeSelectionCount(field, options, true)).toBe(false)
    expect(options.selection).toMatchObject({
      directory: 'ORGANIZATION',
      rootIds: ['root'],
      includeDescendants: true,
      organizationTypes: [1],
      defaultMode: 'FIXED'
    })
    expect(options.defaultValue).toBe('["org-a"]')
    expect(changeSelectionCount(field, options, false)).toBe(false)
    expect(options.defaultValue).toBe('org-a')
    changeSelectionCount(field, options, true)
    options.defaultValue = '["org-a","org-b"]'
    expect(changeSelectionCount(field, options, false)).toBe(true)
    expect(options.defaultValue).toBeNull()
    expect(options.selection).toMatchObject({ rootIds: ['root'], defaultMode: 'NONE' })
  })
  it('公共字典和目录来源未配置完整时在字段配置及统一保存处拦截', () => {
    const design = newDesign()
    const field = { ...newField(1, '分类'), type: 'MULTI_SELECT' as const }
    const source = selectionSource(field)!
    design.draft.fields.push(field)
    const options = {
      ...defaultFieldOptions(),
      selection: { ...source, kind: 'SYSTEM_DICTIONARY' as const, dictionaryType: null }
    }
    design.fieldOptions[field.key] = options
    expect(fieldConfigurationError(field, options)).toContain('平台公共字典')
    expect(designFieldsError(design)?.message).toContain('平台公共字典')
    expect(
      fieldConfigurationError(field, {
        ...options,
        selection: { ...source, kind: 'SYSTEM_DICTIONARY', dictionaryType: 'sys_common_status' }
      })
    ).toBeNull()
    expect(
      fieldConfigurationError(field, { ...options, selection: { ...source, kind: 'DIRECTORY', directory: null } })
    ).toContain('系统目录')
    expect(
      fieldConfigurationError(field, { ...options, selection: { ...source, kind: 'DIRECTORY', directory: 'USER' } })
    ).toBeNull()
    expect(fieldConfigurationError(field, { ...options, selection: { ...source, kind: 'OBJECT_RELATION' } })).toContain(
      '对象关系'
    )
  })
  it('主表和明细新字段连续改名时跟随编码，清空名称同步清空建议', () => {
    for (const field of [newField(0), newField(0, '名称')]) {
      Object.assign(field, fieldNamePatch(field, '资产类型编码'))
      expect(field.code).toBe('c_zclxbm')
      Object.assign(field, fieldNamePatch(field, '资产类型名称'))
      expect(field.code).toBe('c_zclxmc')
      Object.assign(field, fieldNamePatch(field, ''))
      expect(field.code).toBe('')
    }
    expect(newDesign().draft.fields[0].code).toBe('c_mc')
  })
  it('手填编码保持原样，清空编码后再次改名恢复自动建议', () => {
    const field = newField(0, '资产类型')
    field.code = 'asset_type'
    Object.assign(field, fieldNamePatch(field, '资产分类'))
    expect(field.code).toBe('asset_type')
    field.code = ''
    Object.assign(field, fieldNamePatch(field, '所属组织'))
    expect(field.code).toBe('c_sszz')
  })
  it('已保存字段、纳管字段及生成或主键映射改名不自动改编码', () => {
    const source = newField(0, '名称')
    expect(fieldNamePatch({ ...source, id: '31' }, '资产名称')).toEqual({ name: '资产名称' })
    expect(fieldNamePatch(source, '资产名称', undefined, true)).toEqual({ name: '资产名称' })
    for (const flags of [{ generated: true }, { primaryKey: true }]) {
      expect(fieldNamePatch(source, '资产名称', { ...defaultFieldOptions(), ...flags })).toEqual({ name: '资产名称' })
    }
  })
  it('配置弹窗改名只修改副本，同名自动编码仍受重复校验', () => {
    const source = newField(0, '资产类型编码')
    const edited = { ...source }
    Object.assign(edited, fieldNamePatch(edited, '所属组织'))
    expect(source).toMatchObject({ name: '资产类型编码', code: 'c_zclxbm' })
    expect(edited).toMatchObject({ name: '所属组织', code: 'c_sszz' })
    const duplicate = newField(1, '所属组织')
    expect(fieldBasicsError(edited, [edited, duplicate])).toContain('重复')
  })
  it('系统字段展示不改写对象，明细附带父键且不自动添加候选字段', () => {
    const design = newDesign()
    const before = JSON.stringify(design)
    const rows = systemFields(design.mainBinding, design.draft.fields, design.fieldOptions)
    expect(rows.map(f => f.code)).toEqual(['id', 'deleted', 'create_time', 'creator', 'update_time', 'updater'])
    rows[0].name = '临时修改'
    expect(systemFields(design.mainBinding, [], {})[0].name).toBe('记录 ID')
    expect(systemFields(generatedBinding('public', true), [], {}).at(-1)?.code).toBe('parent_id')
    expect(JSON.stringify(design)).toBe(before)
  })
  it('纳管对象仅展示现有映射，保留自定义主键和类型，不补齐审计列', () => {
    const binding = { ...generatedBinding(), source: 'ADOPTED' as const, keyColumn: 'record_key' }
    const key = { ...newField(0), code: 'key', name: '旧主键' }
    const data = { ...newField(1), code: 'name', name: '名称' }
    const options = {
      [key.key]: { ...defaultFieldOptions(), primaryKey: true, columnName: 'record_key', nativeType: 'uuid' }
    }
    expect(systemFields(binding, [key, data], options)).toEqual([
      expect.objectContaining({ code: 'record_key', type: 'uuid', rule: '沿用已有字段定义' })
    ])
    expect(systemFields(binding, [], {})).toEqual([])
    const actual = [
      { name: 'record_key', nativeType: 'uuid', primaryKeyPosition: 1 },
      { name: 'creator', nativeType: 'character varying(64)', primaryKeyPosition: 0 },
      { name: 'deleted', nativeType: 'smallint', primaryKeyPosition: 0 },
      { name: 'name', nativeType: 'text', primaryKeyPosition: 0 }
    ] as any
    expect(systemFields(binding, [key, data], options, actual).map(f => f.code)).toEqual([
      'record_key',
      'creator',
      'deleted'
    ])
    expect(systemFields(binding, [], {}, [actual[0]]).map(f => f.code)).toEqual(['record_key'])
  })
  it('手工输入保留编码后仍可修正，持久化的系统映射和生成字段保持受保护', () => {
    const field = { ...newField(0), code: 'id', name: '标识' }
    expect(fieldBasicsError(field, [field])).toContain('系统字段保留')
    expect(fieldStructureLocked(field)).toBe(false)
    expect(fieldStructureLocked(field, { ...defaultFieldOptions(), generated: true })).toBe(true)
    expect(fieldStructureLocked(field, undefined, true)).toBe(true)
    expect(fieldStructureLocked({ ...field, id: '31' })).toBe(false)
    expect(fieldStructureLocked({ ...field, id: '31' }, { ...defaultFieldOptions(), columnName: 'id' })).toBe(true)
  })
  it('类型切换清除旧配置，保留字段身份和说明；重复选中同类型不丢配置', () => {
    const field = { ...newField(0), id: '31', name: '选项', code: 'status', type: 'SELECT' as const }
    const options = {
      ...defaultFieldOptions(),
      description: '保留说明',
      pattern: '旧规则',
      options: [{ code: 'a', label: 'A', disabled: false }]
    }
    changeFieldType(field, options, 'SELECT')
    expect(options.options).toHaveLength(1)
    changeFieldType(field, options, 'DECIMAL')
    expect([field.id, field.length, field.precision, field.scale]).toEqual(['31', null, 18, 2])
    expect(options).toMatchObject({ description: '保留说明', options: [], pattern: null })
  })
  it('配置弹窗副本可取消，不污染行中已有选项', () => {
    const options = { ...defaultFieldOptions(), options: [{ code: 'a', label: 'A', disabled: false }] }
    const copy = copyFieldOptions(options)
    copy.options[0].label = 'B'
    expect(options.options[0].label).toBe('A')
  })
  it('保存前拦截主表重复编码和未完成配置，并定位到所属页签', () => {
    const design = newDesign()
    const field = { ...newField(1), code: design.draft.fields[0].code, name: '状态', type: 'SELECT' as const }
    design.draft.fields.push(field)
    expect(designFieldsError(design)?.message).toContain('重复')
    field.code = 'status'
    expect(designFieldsError(design)).toMatchObject({ tab: 'fields', message: expect.stringContaining('添加选项') })
    design.fieldOptions[field.key] = { ...defaultFieldOptions(), options: [{ code: 'a', label: 'A', disabled: false }] }
    expect(designFieldsError(design)).toBeNull()
  })
  it('保存同时拦截明细公式缺失；停用明细不妨碍保存', () => {
    const design = newDesign()
    design.details.push({
      id: null,
      code: 'items',
      name: '明细',
      tableName: 'nocode_data_test_items',
      state: 'ACTIVE',
      fields: [{ ...newField(0), name: '金额', code: 'amount', type: 'FORMULA' }],
      fieldOptions: {},
      indexes: [],
      binding: generatedBinding('public', true)
    })
    expect(designFieldsError(design)).toMatchObject({ tab: 'details', message: expect.stringContaining('计算规则') })
    design.details[0].state = 'INACTIVE'
    expect(designFieldsError(design)).toBeNull()
  })
  it('数值精度、选项编码和汇总约束不能从行内绕过', () => {
    const field = { ...newField(0), name: '金额', code: 'amount', required: true, unique: true }
    const options = defaultFieldOptions()
    changeFieldType(field, options, 'DECIMAL')
    field.scale = 20
    expect(fieldConfigurationError(field, options)).toContain('小数位数')
    changeFieldType(field, options, 'SUMMARY')
    expect([field.required, field.unique, options.resultType]).toEqual([false, false, 'INTEGER'])
    changeFieldType(field, options, 'SELECT')
    options.options = [
      { code: 'a', label: 'A', disabled: false },
      { code: 'a', label: 'B', disabled: false }
    ]
    expect(fieldConfigurationError(field, options)).toContain('选项编码重复')
  })
  it('金额默认值与上下限实时按十进制比较，草稿保存不能绕过', () => {
    const design = newDesign()
    const field = { ...newField(1, '金额'), code: 'amount' }
    const options = defaultFieldOptions()
    changeFieldType(field, options, 'MONEY')
    field.precision = 30
    design.draft.fields.push(field)
    design.fieldOptions[field.key] = options
    options.defaultValue = '123123.00'
    options.minimum = '1111111111111.00'
    expect(fieldNumericConstraintError(field, options)).toMatchObject({
      input: 'defaultValue',
      message: expect.stringContaining('不能小于最小值')
    })
    expect(fieldConfigurationError(field, options)).toContain('不能小于最小值')
    expect(designFieldsError(design)).toMatchObject({
      tab: 'fields',
      message: expect.stringContaining('不能小于最小值')
    })
    options.defaultValue = '1111111111111.00'
    expect(fieldNumericConstraintError(field, options)).toBeNull()
    options.maximum = '1111111111110.99'
    expect(fieldNumericConstraintError(field, options)).toMatchObject({ input: 'maximum' })
    options.maximum = '9007199254740993.01'
    options.defaultValue = '9007199254740993.02'
    expect(fieldNumericConstraintError(field, options)).toMatchObject({
      input: 'defaultValue',
      message: expect.stringContaining('不能大于最大值')
    })
    options.defaultValue = '9007199254740993.01'
    expect(fieldNumericConstraintError(field, options)).toBeNull()
    expect(designFieldsError(design)).toBeNull()
  })
  it('整数等数值字段同样校验默认值、非法边界和精度', () => {
    const field = { ...newField(0, '数量'), type: 'INTEGER' as const }
    const options = { ...defaultFieldOptions(), defaultValue: '-2', minimum: '-1' }
    expect(fieldNumericConstraintError(field, options)?.message).toContain('不能小于最小值')
    options.minimum = '错误'
    expect(fieldNumericConstraintError(field, options)).toMatchObject({ input: 'minimum' })
    options.minimum = '-2'
    expect(fieldNumericConstraintError(field, options)).toBeNull()
    options.defaultValue = '9223372036854775808'
    expect(fieldNumericConstraintError(field, options)?.message).toContain('64 位整数范围')
  })
  it('百分比默认值低于最小值时拦截，等于边界后允许保存', () => {
    const field = { ...newField(0, '完成率'), type: 'PERCENT' as const, precision: 18, scale: 2 }
    const options = { ...defaultFieldOptions(), defaultValue: '123123.00', minimum: '111111111111111.00' }
    expect(fieldNumericConstraintError(field, options)).toMatchObject({ input: 'defaultValue' })
    expect(fieldConfigurationError(field, options)).toContain('默认值不能小于最小值')
    options.defaultValue = options.minimum
    expect(fieldNumericConstraintError(field, options)).toBeNull()
    expect(fieldConfigurationError(field, options)).toBeNull()
  })
  it('错误正则在配置和整张对象保存前定位，修正后允许保存', () => {
    const design = newDesign()
    const field = { ...newField(1, '物品编码'), code: 'item_code', type: 'TEXT' as const }
    design.draft.fields.push(field)
    const options = { ...defaultFieldOptions(), pattern: '[A-' }
    design.fieldOptions[field.key] = options
    expect(fieldPatternError(field, options)).toContain('正则表达式无效')
    expect(fieldConfigurationError(field, options)).toContain('正则表达式无效')
    expect(designFieldsError(design)).toMatchObject({ tab: 'fields', message: expect.stringContaining('物品编码') })
    options.pattern = '^[A-Z0-9]+$'
    expect(fieldConfigurationError(field, options)).toBeNull()
    expect(designFieldsError(design)).toBeNull()
    options.pattern = '  '
    expect(fieldPatternError(field, options)).toBeNull()
    options.pattern = '(?i)^[a-z]+$'
    expect(fieldPatternError(field, options)).toBeNull()
    options.pattern = '(?i)['
    expect(fieldPatternError(field, options)).toContain('正则表达式无效')
    options.pattern = '怕【哦【'
    expect(fieldPatternError(field, options)).toBeNull()
  })
  it('多行文本不保留旧正则，单行文本规则不受影响', () => {
    const multiline = { ...newField(0, '说明'), type: 'TEXTAREA' as const }
    const single = { ...newField(1, '编号'), type: 'TEXT' as const }
    const options = {
      [multiline.key]: { ...defaultFieldOptions(), pattern: '[A-' },
      [single.key]: { ...defaultFieldOptions(), pattern: '^[A-Z]+$' }
    }
    expect(fieldPatternError(multiline, options[multiline.key])).toBeNull()
    clearMultilinePatterns([multiline, single], options)
    expect(options[multiline.key].pattern).toBeNull()
    expect(options[single.key].pattern).toBe('^[A-Z]+$')
    options[single.key].pattern = '[A-'
    changeFieldType(single, options[single.key], 'TEXTAREA')
    expect(options[single.key].pattern).toBeNull()
  })
  it('内部明细字段也会在草稿保存前指出错误正则', () => {
    const design = newDesign()
    const field = { ...newField(0, '规格型号'), code: 'specification', type: 'TEXT' as const }
    design.details.push({
      id: null,
      code: 'items',
      name: '采购明细',
      tableName: 'biz_purchase_items',
      state: 'ACTIVE',
      fields: [field],
      fieldOptions: { [field.key]: { ...defaultFieldOptions(), pattern: '(' } },
      indexes: [],
      binding: generatedBinding('public', true)
    })
    expect(designFieldsError(design)).toMatchObject({ tab: 'details', message: expect.stringContaining('规格型号') })
  })
})
