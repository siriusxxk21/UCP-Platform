import { describe, expect, it } from 'vitest'
import { defaultFieldOptions, newDesign } from './data-center'
import {
  businessAttachFields,
  businessDirectoryPreview,
  businessFileEnabled,
  businessFileIssues,
  businessGroupFields,
  businessLabelFields,
  businessRecordTitlePreview
} from './business-file-policy'
import { FieldType, MemberState } from '@/types/nocode/enums'
import type { BusinessFilePolicy, SaveDesign } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'

const field = (key: string, name: string, type: ObjectField['type']): ObjectField => ({
  key,
  id: null,
  code: key,
  name,
  type,
  length: null,
  precision: null,
  scale: null,
  required: false,
  unique: false,
  sort: 0
})

function sampleDesign(): SaveDesign {
  const design = newDesign()
  design.draft.fields = [
    field('f-contract', '合同编号', FieldType.TEXT),
    field('f-name', '合同名称', FieldType.TEXT),
    field('f-project', '所属项目', FieldType.REFERENCE),
    field('f-signed', '签订日期', FieldType.DATE),
    field('f-remark', '说明', FieldType.TEXTAREA),
    field('f-sum', '汇总金额', FieldType.FORMULA),
    field('f-file', '签署版合同', FieldType.ATTACHMENT),
    field('f-extra', '补充协议', FieldType.IMAGE),
    field('f-temp', '临时说明附件', FieldType.ATTACHMENT)
  ]
  design.details = [
    {
      id: 'd-pay',
      code: 'pay',
      name: '付款计划',
      tableName: 'biz_pay',
      state: MemberState.ACTIVE,
      fields: [field('d-receipt', '回单', FieldType.ATTACHMENT), field('d-note', '备注', FieldType.TEXT)],
      fieldOptions: { 'd-receipt': defaultFieldOptions() },
      indexes: []
    },
    {
      id: 'd-retired',
      code: 'retired',
      name: '停用明细',
      tableName: 'biz_retired',
      state: MemberState.INACTIVE,
      fields: [field('d-photo', '现场照片', FieldType.IMAGE)],
      fieldOptions: {},
      indexes: []
    }
  ]
  design.settings.businessFilePolicy = policy({
    spaceId: '1001',
    spaceName: '经营资料',
    fixedPath: ['采购合同'],
    groups: [
      { fieldId: 'f-signed', format: 'YEAR' },
      { fieldId: 'f-project', format: null }
    ],
    recordLabelFields: ['f-contract', 'f-name'],
    fieldIds: ['f-file']
  })
  return design
}

function policy(value: Partial<BusinessFilePolicy>): BusinessFilePolicy {
  return {
    spaceId: null,
    spaceName: '',
    fixedPath: [],
    groups: [],
    recordLabelFields: [],
    fieldIds: [],
    ...value
  }
}

/** 取已写入的设计规则引用；样例设计保证存在。 */
function policyOf(design: SaveDesign): BusinessFilePolicy {
  const value = design.settings.businessFilePolicy
  if (!value) throw new Error('样例设计缺少业务文件规则')
  return value
}

describe('业务文件接入规则', () => {
  it('启用判定要求空间名称与参与字段齐备', () => {
    expect(businessFileEnabled(null)).toBe(false)
    expect(businessFileEnabled(policy({ spaceName: '经营资料' }))).toBe(false)
    expect(businessFileEnabled(policy({ fieldIds: ['f-file'] }))).toBe(false)
    expect(businessFileEnabled(policy({ spaceName: '经营资料', fieldIds: ['f-file'] }))).toBe(true)
  })
  it('候选随字段类型与明细状态过滤', () => {
    const design = sampleDesign()
    const groups = businessGroupFields(design).map(f => f.key)
    expect(groups).toContain('f-project')
    expect(groups).toContain('f-signed')
    expect(groups).not.toContain('f-remark')
    const labels = businessLabelFields(design).map(f => f.key)
    expect(labels).toContain('f-remark')
    expect(labels).not.toContain('f-file')
    expect(labels).not.toContain('f-sum')
    const attach = businessAttachFields(design)
    expect(attach.map(item => item.key)).toEqual(['f-file', 'f-extra', 'f-temp', 'd-receipt'])
    expect(attach[3].label).toBe('付款计划 · 回单')
  })
  it('问题清单镜像服务端校验', () => {
    const design = sampleDesign()
    expect(businessFileIssues(design)).toEqual([])
    design.settings.businessFilePolicy = policy({ spaceName: ' ', fieldIds: ['f-file'] })
    expect(businessFileIssues(design)).toContain('请选择已有业务空间')
    expect(businessFileIssues(design)).toContain('业务空间名称不能为空或超过 64 字')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      fixedPath: ['a', 'b', 'c', 'd', 'e', 'f'],
      fieldIds: ['f-file']
    })
    expect(businessFileIssues(design)).toContain('固定目录最多 5 层')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      fixedPath: ['采/购'],
      fieldIds: ['f-file']
    })
    expect(businessFileIssues(design)).toContain('固定目录名称不能包含路径分隔符或以点开头')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      groups: [
        { fieldId: 'f-remark', format: null },
        { fieldId: 'f-contract', format: 'YEAR' }
      ],
      fieldIds: ['f-file']
    })
    const issues = businessFileIssues(design)
    expect(issues).toContain('业务分组仅支持主表文本、单选、日期或单值关联字段：说明')
    expect(issues).toContain('年份/年月格式仅适用于日期字段：合同编号')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      groups: [
        { fieldId: 'f-project', format: null },
        { fieldId: 'f-project', format: null }
      ],
      fieldIds: ['f-file']
    })
    expect(businessFileIssues(design)).toContain('业务分组字段重复')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      recordLabelFields: ['f-contract', 'f-contract', 'f-name', 'f-remark', 'f-project', 'f-signed'],
      fieldIds: []
    })
    const labelIssues = businessFileIssues(design)
    expect(labelIssues).toContain('记录目录名称最多引用 5 个字段')
    expect(labelIssues).toContain('接入业务网盘时至少选择一个附件或图片字段')
    design.settings.businessFilePolicy = policy({
      spaceName: '经营资料',
      fieldIds: ['f-file', 'f-file', 'f-missing', 'f-name']
    })
    const attachIssues = businessFileIssues(design)
    expect(attachIssues).toContain('接入字段重复')
    expect(attachIssues).toContain('接入字段不存在或已停用')
  })
  it('字段移除或停用后规则引用报悬空', () => {
    const design = sampleDesign()
    design.draft.fields = design.draft.fields.filter(f => f.key !== 'f-project')
    expect(businessFileIssues(design)).toContain('业务分组必须引用主表有效字段')
    policyOf(design).recordLabelFields = ['f-name']
    design.fieldOptions['f-name'] = { ...defaultFieldOptions(), state: MemberState.INACTIVE }
    expect(businessFileIssues(design)).toContain('记录目录名称必须引用主表有效字段')
  })
  it('默认复用对象标题，模板字段更名与自定义名称优先级一致', () => {
    const design = sampleDesign()
    policyOf(design).recordLabelFields = []
    design.draft.titleFieldKey = 'f-name'
    expect(businessRecordTitlePreview(design)).toBe('{合同名称}')
    design.draft.fields[0].code = 'contract_no'
    design.settings.titleTemplate = '合同 {{ contract_no }}'
    expect(businessRecordTitlePreview(design)).toBe('合同 {合同编号}')
    policyOf(design).recordLabelFields = ['f-name']
    expect(businessRecordTitlePreview(design)).toBe('{合同名称}')
    policyOf(design).recordLabelFields = ['missing']
    expect(businessRecordTitlePreview(design)).toBe('（名称配置失效）')
  })
  it('目录预览按模板层级展开并示意取值', () => {
    const design = sampleDesign()
    expect(businessDirectoryPreview(design)).toEqual([
      '经营资料',
      '采购合同',
      String(new Date().getFullYear()),
      '{所属项目}',
      '{合同编号} · {合同名称}',
      '签署版合同'
    ])
    policyOf(design).recordLabelFields = []
    design.draft.titleFieldKey = 'f-name'
    expect(businessDirectoryPreview(design).at(-2)).toBe('{合同名称}')
    policyOf(design).groups = [{ fieldId: 'f-signed', format: 'MONTH' }]
    expect(businessDirectoryPreview(design)[2]).toMatch(/^\d{4}-\d{2}$/)
    policyOf(design).fieldIds = ['f-file', 'f-extra']
    expect(businessDirectoryPreview(design).at(-1)).toBe('签署版合同 等 2 个字段分组')
    design.settings.businessFilePolicy = null
    expect(businessDirectoryPreview(design)).toEqual([])
  })
})
