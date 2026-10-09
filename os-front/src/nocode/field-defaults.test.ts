import { describe, expect, it } from 'vitest'
import { decodeFieldDefault, encodeFieldDefault, fieldDefaultError } from './field-defaults'
import { newField } from './object-draft'
import { defaultFieldOptions } from './data-center'
import { FieldType } from '@/types/nocode/enums'
import { directoryDefaultOptions } from './directory-scope-options'
describe('字段类型默认值契约', () => {
  it('多行文本默认值往返时保留换行', () => {
    const field = { ...newField(0), type: FieldType.TEXTAREA },
      value = '第一行\n第二行'
    expect(decodeFieldDefault(field, encodeFieldDefault(field, value))).toBe(value)
  })
  it('合法日期可往返，中文日期和不存在的日期在保存前阻止', () => {
    const field = { ...newField(0), type: FieldType.DATE },
      option = defaultFieldOptions()
    for (const value of ['明天', '2026-02-30', '2026-13-01'])
      expect(fieldDefaultError(field, { ...option, defaultValue: value })).not.toBeNull()
    expect(fieldDefaultError(field, { ...option, defaultValue: '2028-02-29' })).toBeNull()
  })
  it('默认 false 和 0 不等于未设置；高精度数字以字符串往返', () => {
    for (const value of ['0', '9007199254740993', '123456789.123456789']) {
      const field = { ...newField(0), type: FieldType.DECIMAL }
      expect(decodeFieldDefault(field, encodeFieldDefault(field, value))).toBe(value)
    }
    expect(encodeFieldDefault({ ...newField(0), type: FieldType.BOOLEAN }, false)).toBe('false')
    expect(encodeFieldDefault(newField(0), null)).toBeNull()
  })
  it('多选与文件序列化为真实数组，单选保持稳定编码，旧默认项失效明确报错', () => {
    for (const type of [FieldType.MULTI_SELECT, FieldType.IMAGE, FieldType.ATTACHMENT]) {
      const field = { ...newField(0), type }
      expect(decodeFieldDefault(field, encodeFieldDefault(field, ['10', '20']))).toEqual(['10', '20'])
      expect(encodeFieldDefault(field, [])).toBeNull()
    }
    const field = { ...newField(0), type: FieldType.SELECT },
      option = { ...defaultFieldOptions(), options: [{ code: 'a', label: '供应商A', disabled: false }] }
    expect(fieldDefaultError(field, { ...option, defaultValue: 'a' })).toBeNull()
    expect(fieldDefaultError(field, { ...option, defaultValue: 'b' })).toContain('移除或停用')
  })
  it('链接保存展示文字及地址，时间格式错误不会当普通文字放行', () => {
    const field = { ...newField(0), type: FieldType.URL },
      value = { link: 'https://example.com', text: '网站' }
    expect(decodeFieldDefault(field, encodeFieldDefault(field, value))).toEqual(value)
    expect(
      fieldDefaultError({ ...field, type: FieldType.TIME }, { ...defaultFieldOptions(), defaultValue: '25:00:00' })
    ).toContain('时间')
  })
  it('时间戳与带时区时间戳按各自格式验证，链接校验可阻止外层保存', () => {
    const field = { ...newField(0), type: FieldType.DATETIME },
      option = defaultFieldOptions()
    expect(fieldDefaultError(field, { ...option, defaultValue: '2026-02-30 12:00:00' })).not.toBeNull()
    expect(fieldDefaultError(field, { ...option, defaultValue: '2026-09-13 12:00:00' })).toBeNull()
    expect(
      fieldDefaultError(field, {
        ...option,
        nativeType: 'timestamp with time zone',
        defaultValue: '2026-09-13T12:00:00+08:00'
      })
    ).toBeNull()
    expect(
      fieldDefaultError(field, {
        ...option,
        nativeType: 'timestamp with time zone',
        defaultValue: '2026-09-13 12:00:00'
      })
    ).not.toBeNull()
    expect(
      fieldDefaultError(
        { ...field, type: FieldType.URL },
        { ...option, defaultValue: JSON.stringify({ link: 'javascript:alert(1)', text: '危险' }) }
      )
    ).not.toBeNull()
  })
  it('目录默认值候选遵守根范围、下级、组织类型与停用状态', () => {
    const options = [
      { value: '1', label: '总部', organizationType: 1 },
      { value: '2', label: '部门', parentValue: '1', organizationType: 3 },
      { value: '3', label: '外部', organizationType: 3 },
      { value: '4', label: '停用', parentValue: '1', organizationType: 3, disabled: true }
    ]
    const source = {
      kind: 'DIRECTORY',
      directory: 'ORGANIZATION',
      dictionaryType: null,
      rootIds: ['1'],
      includeDescendants: true,
      organizationTypes: [3],
      defaultMode: 'FIXED'
    } as const
    expect(
      directoryDefaultOptions(options, {
        ...source,
        rootIds: [...source.rootIds],
        organizationTypes: [...source.organizationTypes]
      }).map(item => item.value)
    ).toEqual(['2'])
  })
})
