import { effectScope, reactive } from 'vue'
import { describe, expect, it } from 'vitest'
import { ResourceKind } from '../types/nocode/application'
import { fieldCode, resourceCode, suggestedTableName, suggestedDetailTableName, useResourceCode } from './resource-code'

describe('资源名称生成编码', () => {
  it('字段使用 c_ 前缀和中文拼音首字母，保留英文数字并限制 63 字符', () => {
    expect(fieldCode('资产类型编码')).toBe('c_zclxbm')
    expect(fieldCode('所属组织')).toBe('c_sszz')
    expect(fieldCode('重庆银行')).toBe('c_cqyh')
    expect(fieldCode('zclx')).toBe('c_zclx')
    expect(fieldCode(' 资产 ID_2026（总部） ')).toBe('c_zcid_2026zb')
    expect(fieldCode('123')).toBe('c_123')
    expect(fieldCode('公司'.repeat(100))).toHaveLength(63)
    expect(fieldCode('')).toBe('')
    expect(fieldCode(' ！_🙂 ')).toBe('')
  })
  it('按资源类型生成中文拼音首字母，覆盖全部应用资源', () => {
    expect(resourceCode('公司', 'OBJECT')).toBe('object_gs')
    expect(resourceCode('公司', 'APP')).toBe('app_gs')
    for (const kind of Object.values(ResourceKind)) expect(resourceCode('公司', kind)).toBe(`${kind.toLowerCase()}_gs`)
    expect(resourceCode('公司营业流水', 'OBJECT')).toBe('object_gsyyls')
  })

  it('处理多音词、中英文、数字和无有效字符的输入', () => {
    expect(resourceCode('重庆银行', 'APP')).toBe('app_cqyh')
    expect(resourceCode(' 公司 CRM_2026（总部） ', 'FORM')).toBe('form_gscrm_2026zb')
    expect(resourceCode('', 'APP')).toBe('')
    expect(resourceCode(' ！_🙂 ', 'OBJECT')).toBe('')
    expect(resourceCode('123', 'PAGE')).toBe('page_123')
  })

  it('限制编码和默认物理表长度，自定义物理名不被覆盖', () => {
    const code = resourceCode('公司'.repeat(100), 'OBJECT')
    expect(code).toHaveLength(59)
    expect(suggestedTableName(code)).toHaveLength(63)
    expect(resourceCode('公司'.repeat(100), 'FORM')).toHaveLength(64)
    expect(suggestedTableName('object_kh', 'object_gs', 'biz_object_gs')).toBe('biz_object_kh')
    expect(suggestedTableName('object_kh', 'object_gs', 'biz_custom')).toBe('biz_custom')
    expect(suggestedTableName('', 'object_gs', 'biz_object_gs')).toBe('')
    expect(suggestedTableName('object_kh', 'object_gs', 'nocode_data_object_gs')).toBe('nocode_data_object_gs')
  })

  it('新明细采用 biz_，兼容旧主表名称并为后缀保留长度', () => {
    expect(suggestedDetailTableName('biz_object_gs', 'items_1')).toBe('biz_object_gs_items_1')
    expect(suggestedDetailTableName('nocode_data_company', 'items_1')).toBe('biz_company_items_1')
    expect(suggestedDetailTableName('biz_' + 'a'.repeat(59), 'items_1')).toHaveLength(63)
    expect(suggestedDetailTableName('biz_' + 'a'.repeat(59), 'items_1')).toMatch(/_items_1$/)
  })

  it('新建跟随名称，手填后保留，清空恢复；已存资源改名不改编码，重开新建重置', () => {
    const scope = effectScope()
    const form = reactive({ name: '', code: '', creating: true })
    const suggestion = scope.run(() =>
      useResourceCode({
        name: () => form.name,
        kind: () => 'FORM',
        enabled: () => form.creating,
        setCode: code => {
          form.code = code
        }
      })
    )!
    try {
      form.name = '公司'
      expect(form.code).toBe('form_gs')
      form.name = '客户'
      expect(form.code).toBe('form_kh')
      suggestion.changeCode('form_custom')
      form.name = '公司管理'
      expect(form.code).toBe('form_custom')
      suggestion.changeCode('')
      form.name = '公司'
      expect(form.code).toBe('form_gs')
      form.name = ''
      expect(form.code).toBe('')
      form.name = '公司'
      form.creating = false
      form.name = '更名公司'
      expect(form.code).toBe('form_gs')
      suggestion.changeCode('manual')
      suggestion.reset()
      form.creating = true
      form.name = '营业流水'
      expect(form.code).toBe('form_yyls')
    } finally {
      scope.stop()
    }
  })
})
