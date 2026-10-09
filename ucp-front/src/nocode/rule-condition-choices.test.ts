import { describe, expect, it, vi } from 'vitest'
import { FieldType } from '@/types/nocode/enums'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { SelectionSource } from '@/types/nocode/selection'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import type { PublishedDefinition } from './field-rules'
import {
  conditionChoiceOptions,
  isChoiceConditionField,
  loadConditionChoices,
  loadFieldChoices,
  type ConditionChoiceDeps
} from './rule-condition-choices'

const field = (type: string, id: string, name = id): ObjectField => ({
  ...newField(0, name),
  key: id,
  id,
  code: id,
  type: type as ObjectField['type']
})
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
const selection = (patch: Partial<SelectionSource>): SelectionSource => ({
  kind: 'LOCAL_OPTIONS',
  directory: null,
  dictionaryType: null,
  rootIds: [],
  includeDescendants: false,
  organizationTypes: [],
  defaultMode: 'NONE',
  ...patch
})
const definition = (
  objectId: string,
  fields: ObjectField[],
  fieldOptions: Record<string, FieldOptions>
): PublishedDefinition => ({ objectId, label: objectId, fields, fieldOptions, relations: [], titleTemplate: null })
const deps = (patch: Partial<ConditionChoiceDeps> = {}): ConditionChoiceDeps => ({
  definition: vi.fn(async () => {
    throw new Error('不应读取其它对象')
  }),
  dictionary: vi.fn(async () => {
    throw new Error('不应读取字典')
  }),
  ...patch
})

describe('条件固定值的选项（与后端 FieldRuleValidator.choiceCodes 同口径）', () => {
  it('只有单选、多选且来源是选项的字段走下拉；配成组织目录的多选与其它类型不走', () => {
    const d = definition('flow', [], {
      dict: options({ selection: selection({ kind: 'SYSTEM_DICTIONARY', dictionaryType: 'pay_type' }) }),
      picked: options({
        selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'voucher', sourceFieldId: 'vs' })
      }),
      orgs: options({ selection: selection({ kind: 'DIRECTORY', directory: FieldType.ORGANIZATION }) })
    })
    expect(isChoiceConditionField(d, field(FieldType.SELECT, 'plain'))).toBe(true)
    expect(isChoiceConditionField(d, field(FieldType.MULTI_SELECT, 'plain'))).toBe(true)
    expect(isChoiceConditionField(d, field(FieldType.SELECT, 'dict'))).toBe(true)
    expect(isChoiceConditionField(d, field(FieldType.SELECT, 'picked'))).toBe(true)
    expect(isChoiceConditionField(d, field(FieldType.MULTI_SELECT, 'orgs'))).toBe(false)
    for (const type of [FieldType.TEXT, FieldType.INTEGER, FieldType.BOOLEAN, FieldType.USER, FieldType.REFERENCE])
      expect(isChoiceConditionField(d, field(type, 'plain'))).toBe(false)
    expect(isChoiceConditionField(d, null)).toBe(false)
  })

  it('局部选项：按编码去重，保留停用标记，不读其它对象或字典', async () => {
    const status = field(FieldType.SELECT, 'status')
    const d = definition('flow', [status], {
      status: options({
        options: [
          { code: 'ylr', label: '已录入', disabled: false },
          { code: 'zf', label: '作废', disabled: true },
          { code: 'ylr', label: '重复', disabled: false }
        ]
      })
    })
    expect(await loadConditionChoices(d, status, deps())).toEqual({
      options: [
        { value: 'ylr', label: '已录入', disabled: false },
        { value: 'zf', label: '作废', disabled: true }
      ],
      partial: false
    })
  })

  it('挑取值：取它指向的来源字段的选项；来源字段用公共字典时取字典项', async () => {
    const picked = field(FieldType.SELECT, 'picked')
    const flow = definition('flow', [picked], {
      picked: options({
        selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'voucher', sourceFieldId: 'vs' })
      })
    })
    const local = definition('voucher', [field(FieldType.SELECT, 'vs')], {
      vs: options({ options: [{ code: 'ylr', label: '已录入', disabled: false }] })
    })
    const load = vi.fn(async () => local)
    expect(await loadConditionChoices(flow, picked, deps({ definition: load }))).toEqual({
      options: [{ value: 'ylr', label: '已录入', disabled: false }],
      partial: false
    })
    expect(load).toHaveBeenCalledWith('voucher')

    const dictionary = definition('voucher', [field(FieldType.SELECT, 'vs')], {
      vs: options({ selection: selection({ kind: 'SYSTEM_DICTIONARY', dictionaryType: 'voucher_state' }) })
    })
    const items = vi.fn(async () => [{ value: '1', label: '已录入', disabled: false }])
    expect(
      await loadConditionChoices(flow, picked, deps({ definition: async () => dictionary, dictionary: items }))
    ).toEqual({ options: [{ value: '1', label: '已录入', disabled: false }], partial: true })
    expect(items).toHaveBeenCalledWith('voucher_state')
  })

  it('挑取值指向本对象的字段时不另外请求；配置不全或来源字段不存在时报出原因', async () => {
    const picked = field(FieldType.SELECT, 'picked')
    const own = definition('flow', [picked, field(FieldType.SELECT, 'origin')], {
      picked: options({
        selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'flow', sourceFieldId: 'origin' })
      }),
      origin: options({ options: [{ code: 'A', label: '甲', disabled: false }] })
    })
    expect((await loadConditionChoices(own, picked, deps())).options).toEqual([
      { value: 'A', label: '甲', disabled: false }
    ])
    const incomplete = definition('flow', [picked], {
      picked: options({ selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'voucher' }) })
    })
    await expect(loadConditionChoices(incomplete, picked, deps())).rejects.toThrow('还没选来源对象或来源字段')
    const gone = definition('flow', [picked], {
      picked: options({
        selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'voucher', sourceFieldId: 'vs' })
      })
    })
    await expect(
      loadConditionChoices(gone, picked, deps({ definition: async () => definition('voucher', [], {}) }))
    ).rejects.toThrow('已不存在或已停用')
  })

  // 数据联动「没有匹配记录时填入」用的是正在编辑的当前字段：只有它的选项配置，没有所在对象的已发布定义。
  it('按字段的选项配置取选项集：局部选项直接取；挑取值没有本对象定义时读它指向的对象；公共字典取字典项', async () => {
    const local = options({
      options: [
        { code: 'wdj', label: '未登记', disabled: false },
        { code: 'old', label: '作废', disabled: true }
      ]
    })
    expect(await loadFieldChoices(local, deps())).toEqual({
      options: [
        { value: 'wdj', label: '未登记', disabled: false },
        { value: 'old', label: '作废', disabled: true }
      ],
      partial: false
    })
    const voucher = definition('voucher', [field(FieldType.SELECT, 'vs')], {
      vs: options({ options: [{ code: 'DONE', label: '已录入', disabled: false }] })
    })
    const read = vi.fn(async () => voucher)
    const picked = options({
      selection: selection({ kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'voucher', sourceFieldId: 'vs' })
    })
    expect((await loadFieldChoices(picked, deps({ definition: read }))).options).toEqual([
      { value: 'DONE', label: '已录入', disabled: false }
    ])
    expect(read).toHaveBeenCalledWith('voucher')
    const dictionary = vi.fn(async () => [{ value: 'cash', label: '现金', disabled: false }])
    expect(
      await loadFieldChoices(
        options({ selection: selection({ kind: 'SYSTEM_DICTIONARY', dictionaryType: 'pay_type' }) }),
        deps({ dictionary })
      )
    ).toEqual({ options: [{ value: 'cash', label: '现金', disabled: false }], partial: true })
    expect((await loadFieldChoices(null, deps())).options).toEqual([])
  })

  it('下拉只列启用项；已保存的停用值与不在选项里的值补在末尾用于回显且不可重新选入', () => {
    const set = {
      options: [
        { value: 'ylr', label: '已录入', disabled: false },
        { value: 'zf', label: '作废', disabled: true }
      ],
      partial: false
    }
    expect(conditionChoiceOptions(set, null)).toEqual([{ value: 'ylr', label: '已录入' }])
    expect(conditionChoiceOptions(set, '')).toEqual([{ value: 'ylr', label: '已录入' }])
    expect(conditionChoiceOptions(set, 'ylr')).toEqual([{ value: 'ylr', label: '已录入' }])
    expect(conditionChoiceOptions(set, 'zf')).toEqual([
      { value: 'ylr', label: '已录入' },
      { value: 'zf', label: '作废（已停用）', disabled: true }
    ])
    expect(conditionChoiceOptions(set, ['ylr', '已录入', '已录入'])).toEqual([
      { value: 'ylr', label: '已录入' },
      { value: '已录入', label: '已录入（不是有效选项）', disabled: true }
    ])
    // 公共字典接口只给启用项：不在其中的旧值分不清是停用还是从来不是选项。
    expect(conditionChoiceOptions({ options: set.options.slice(0, 1), partial: true }, 'old')).toEqual([
      { value: 'ylr', label: '已录入' },
      { value: 'old', label: 'old（已停用或不是有效选项）', disabled: true }
    ])
  })
})
