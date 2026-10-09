import { describe, expect, it } from 'vitest'
import {
  BUSINESS_DIRECTORY_KINDS,
  BUSINESS_MAINTENANCE_ENTRY,
  businessApplicationId,
  businessCrumbTrail,
  businessDeepLink,
  businessDirectoryKey,
  businessEntryOptions,
  businessFavoriteKeys,
  businessFileAccessible,
  businessFileLocation,
  businessFileSource,
  businessLabelNotice,
  businessLocation,
  businessMarkType,
  businessRecordTarget,
  businessSpaceOptions,
  businessVersionOptions,
  splitLocatedPath
} from './business-file-browse'
import type { BusinessFileDirectory, BusinessFileEntry } from '@/types/nocode/business-file'

const directory = (value: Partial<BusinessFileDirectory>): BusinessFileDirectory => ({
  kind: 'GROUP',
  label: '分组',
  fileCount: 1,
  totalSize: 1,
  recordCount: 1,
  ...value
})

const file = (value: Partial<BusinessFileEntry>): BusinessFileEntry => ({
  fileId: '1',
  entryId: 11,
  name: '合同.pdf',
  size: 1024,
  ...value
})

describe('业务文件浏览入口', () => {
  it('入口取值映射到应用身份；数据维护入口不携带 applicationId', () => {
    expect(businessApplicationId(undefined)).toBeUndefined()
    expect(businessApplicationId(BUSINESS_MAINTENANCE_ENTRY)).toBeUndefined()
    expect(businessApplicationId('1001')).toBe('1001')
  })

  it('入口选项按权限决定是否展示数据维护', () => {
    const apps = [
      { id: '1001', name: '合同管理' },
      { id: '1002', name: '采购管理' }
    ]
    expect(businessEntryOptions(apps, false).map(option => option.value)).toEqual(['1001', '1002'])
    expect(businessEntryOptions(apps, true)[0]).toEqual({ value: BUSINESS_MAINTENANCE_ENTRY, label: '数据维护' })
    expect(businessEntryOptions([], false)).toEqual([])
  })

  it('空间与规则版本选项按服务端返回装配', () => {
    expect(
      businessSpaceOptions([
        {
          objectId: '2001',
          objectName: '合同',
          spaceName: '经营资料',
          currentRuleVersion: 3,
          fileCount: 2,
          totalSize: 10,
          recordCount: 1
        }
      ])
    ).toEqual([{ value: '2001', label: '合同｜经营资料' }])
    expect(
      businessVersionOptions([
        directory({ kind: 'VERSION', ruleVersion: 3, label: '当前规则（v3）' }),
        directory({ kind: 'VERSION', ruleVersion: 2, label: '历史规则（v2）' }),
        directory({ kind: 'GROUP', ruleVersion: 3, label: '销售部' })
      ])
    ).toEqual([
      { value: 3, label: '当前规则（v3）' },
      { value: 2, label: '历史规则（v2）' }
    ])
  })

  it('位置推导保留分组层级顺序与记录/明细身份', () => {
    const crumbs = [
      directory({ kind: 'GROUP', ruleVersion: 3, groupKey: '销售部' }),
      directory({ kind: 'GROUP', ruleVersion: 3, groupKey: '2026-03-05' }),
      directory({ kind: 'RECORD', ruleVersion: 3, recordId: '88' }),
      directory({ kind: 'REGION', ruleVersion: 3, recordId: '88', detailId: 'd-pay' }),
      directory({ kind: 'ROW', ruleVersion: 3, recordId: '88', detailId: 'd-pay', rowId: '901' }),
      directory({ kind: 'FIELD', ruleVersion: 3, recordId: '88', detailId: 'd-pay', rowId: '901', fieldId: 'f-file' })
    ]
    expect(businessLocation(3, crumbs)).toEqual({
      ruleVersion: 3,
      groupKeys: ['销售部', '2026-03-05'],
      recordId: '88',
      detailId: 'd-pay',
      rowId: '901',
      fieldId: 'f-file'
    })
    expect(businessLocation(undefined, [])).toEqual({
      ruleVersion: undefined,
      groupKeys: [],
      recordId: undefined,
      detailId: undefined,
      rowId: undefined,
      fieldId: undefined
    })
  })

  it('定位链把规则版本与面包屑拆开', () => {
    const located = splitLocatedPath([
      directory({ kind: 'VERSION', ruleVersion: 2, label: '历史规则（v2）' }),
      directory({ kind: 'GROUP', ruleVersion: 2, groupKey: '销售部' }),
      directory({ kind: 'RECORD', ruleVersion: 2, recordId: '88' }),
      directory({ kind: 'FIELD', ruleVersion: 2, recordId: '88', fieldId: 'f-file' })
    ])
    expect(located.ruleVersion).toBe(2)
    expect(located.crumbs.map(item => item.kind)).toEqual(['GROUP', 'RECORD', 'FIELD'])
  })

  it('面包屑首项为空间，规则版本只作标题，其余层级可回退', () => {
    const trail = businessCrumbTrail({ objectName: '合同', spaceName: '经营资料' }, 3, [
      directory({ kind: 'GROUP', ruleVersion: 3, groupKey: '销售部', label: '销售部' }),
      directory({ kind: 'RECORD', ruleVersion: 3, recordId: '88', label: 'HT-2026-001' })
    ])
    expect(trail.map(item => item.label)).toEqual(['经营资料', '规则 v3', '销售部', 'HT-2026-001'])
    expect(trail.map(item => item.index)).toEqual([-1, -1, 0, 1])
    expect(businessCrumbTrail({ objectName: '合同', spaceName: '' }, undefined, [])).toEqual([
      { key: 'space', label: '合同', index: -1 }
    ])
  })

  it('目录行身份包含层级各段，分组同名不同记录不冲突', () => {
    const first = businessDirectoryKey(directory({ kind: 'RECORD', ruleVersion: 3, recordId: '88' }))
    const second = businessDirectoryKey(directory({ kind: 'RECORD', ruleVersion: 3, recordId: '89' }))
    expect(first).not.toBe(second)
    expect(businessDirectoryKey(directory({ kind: 'GROUP', ruleVersion: 3, groupKey: '销售部' }))).toContain(
      'GROUP:3:销售部'
    )
    expect(Object.keys(BUSINESS_DIRECTORY_KINDS)).toEqual(['VERSION', 'GROUP', 'RECORD', 'REGION', 'ROW', 'FIELD'])
  })

  it('标记视图映射到标记类型', () => {
    expect(businessMarkType('favorite')).toBe('FAVORITE')
    expect(businessMarkType('recent')).toBe('RECENT')
  })

  it('深链解析要求位置身份齐备且节点编号为有效数字', () => {
    expect(
      businessDeepLink({
        applicationId: '1001',
        objectId: '2001',
        recordId: '88',
        detailId: 'd-pay',
        rowId: '901',
        fieldId: 'f-file',
        entryId: '930001'
      })
    ).toEqual({
      applicationId: '1001',
      objectId: '2001',
      recordId: '88',
      detailId: 'd-pay',
      rowId: '901',
      fieldId: 'f-file',
      entryId: '930001'
    })
    expect(businessDeepLink({ objectId: '2001', recordId: '88', fieldId: 'f-file' })).toBeUndefined()
    expect(businessDeepLink({ objectId: '2001', recordId: '88', fieldId: 'f-file', entryId: 'abc' })).toBeUndefined()
    expect(businessDeepLink({ objectId: '2001', recordId: '88', fieldId: 'f-file', entryId: '0' })).toBeUndefined()
    expect(businessDeepLink({ applicationId: '1001' })).toBeUndefined()
    expect(
      businessDeepLink({ objectId: '2001', recordId: '88', fieldId: 'f-file', entryId: '930001' })?.applicationId
    ).toBeUndefined()
  })

  it('文件位置身份与内容读取口径一致，缺身份不可操作', () => {
    const full = businessFileLocation(undefined, '2001', file({ recordId: '88', fieldId: 'f-file', entryId: 930001 }))
    expect(full).toEqual({
      applicationId: undefined,
      objectId: '2001',
      recordId: '88',
      detailId: undefined,
      rowId: undefined,
      fieldId: 'f-file',
      entryId: 930001
    })
    const bare = businessFileLocation('1001', '2001', file({}))
    expect(bare.recordId).toBe('')
    expect(bare.fieldId).toBe('')
    expect(businessFileAccessible(file({ recordId: '88', fieldId: 'f-file' }))).toBe(true)
    expect(businessFileAccessible(file({ recordId: '88' }))).toBe(false)
    expect(businessFileAccessible(file({ recordId: null, fieldId: 'f-file' }))).toBe(false)
  })

  it('收藏键按节点编号归一，返回业务记录按入口选择路由', () => {
    expect(businessFavoriteKeys([file({ entryId: 930001 }), file({ entryId: '930002' })])).toEqual(
      new Set(['930001', '930002'])
    )
    expect(businessRecordTarget('1001', '2001', '88')).toEqual({
      path: '/nocode-app/runtime',
      query: { id: '1001', objectId: '2001', recordId: '88' }
    })
    expect(businessRecordTarget(undefined, '2001', '88')).toEqual({
      path: '/nocode/object/editor',
      query: { id: '2001', tab: 'data', recordIds: '88' }
    })
  })
})

describe('业务归属说明', () => {
  it('新状态优先于历史受限标记，空标题与配置失效不冒充无权限', () => {
    expect(businessLabelNotice('NORMAL', true)).toBeUndefined()
    expect(businessLabelNotice('EMPTY', true)?.label).toBe('未填写标题')
    expect(businessLabelNotice('INVALID')?.label).toBe('名称暂不可用')
    expect(businessLabelNotice(undefined, true)?.label).toBe('名称不可见')
  })
  it('附件来源保留明细和行上下文，不以技术字段 ID 代替名称', () => {
    expect(businessFileSource(file({ fieldLabel: '合同附件' }))).toBe('合同附件')
    expect(businessFileSource(file({ detailLabel: '付款计划', rowLabel: '首付款', fieldLabel: '回单' }))).toBe(
      '付款计划 · 首付款 · 回单'
    )
    expect(businessFileSource(file({ fieldId: '123', detailId: '456' }))).toBe('附件')
  })
})
