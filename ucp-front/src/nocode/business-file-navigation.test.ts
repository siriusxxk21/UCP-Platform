import { describe, expect, it } from 'vitest'
import {
  businessNavigationKey,
  businessNavigationSelection,
  businessNavigationTree,
  type BusinessNavigationEntry
} from './business-file-navigation'
import type { BusinessFileSpace } from '@/types/nocode/business-file'
const space = (objectId: string, objectName: string, fileCount = 1): BusinessFileSpace => ({
  objectId,
  objectName,
  spaceName: '归档空间',
  currentRuleVersion: 4,
  fileCount,
  totalSize: fileCount * 1024,
  recordCount: fileCount
})
const entries = (): BusinessNavigationEntry[] => [
  { key: 'finance', label: '财务', loading: false, spaces: [space('company', '公司')] },
  {
    key: 'sales',
    label: '销售 CRM',
    loading: false,
    spaces: [space('company', '客户公司'), space('order', '销售订单')]
  }
]
describe('业务文件应用与对象树', () => {
  it('同一对象保留应用入口身份且复合键不因分隔字符碰撞', () => {
    const tree = businessNavigationTree(entries())
    expect(tree.map(node => node.selectable)).toEqual([false, false])
    expect(tree[0]?.children?.[0]).toMatchObject({ title: '公司', description: '归档空间', selectable: true })
    expect(tree[0]?.children?.[0]?.key).not.toBe(tree[1]?.children?.[0]?.key)
    expect(businessNavigationKey('a:b', 'c')).not.toBe(businessNavigationKey('a', 'b:c'))
    expect(businessNavigationKey('finance')).not.toBe(businessNavigationKey('finance', ''))
  })
  it('对象搜索覆盖全部已加载入口而不依赖展开状态', () => {
    const tree = businessNavigationTree(entries(), '  公司  ')
    expect(tree.map(node => node.entryKey)).toEqual(['finance', 'sales'])
    expect(tree.flatMap(node => node.children?.map(child => child.objectId) || [])).toEqual(['company', 'company'])
    expect(businessNavigationTree(entries(), '订单')).toHaveLength(1)
    expect(businessNavigationTree(entries(), '不存在')).toEqual([])
  })
  it('匹配应用名称时保留该入口全部对象并忽略大小写', () => {
    const tree = businessNavigationTree(entries(), ' crm ')
    expect(tree).toHaveLength(1)
    expect(tree[0]?.children?.map(node => node.objectId)).toEqual(['company', 'order'])
  })
  it('空入口、加载中和失败提示不能成为可选业务对象', () => {
    const tree = businessNavigationTree([
      { key: 'empty', label: '空应用', spaces: [], loading: false },
      { key: 'loading', label: '加载应用', spaces: [], loading: true },
      { key: 'failed', label: '失败应用', spaces: [], loading: false, error: '连接失败' }
    ])
    expect(tree.map(node => node.children?.[0]?.title)).toEqual([
      '暂无可见业务文件',
      '正在加载业务对象…',
      '加载失败，可重试'
    ])
    expect(tree.every(node => node.children?.every(child => child.disabled && !child.selectable))).toBe(true)
    expect(tree[2]?.error).toBe('连接失败')
  })
  it('深链严格限定原应用与对象，不回落其他有权限入口', () => {
    expect(businessNavigationSelection(entries(), 'sales', 'company')).toEqual({
      entryKey: 'sales',
      objectId: 'company'
    })
    expect(businessNavigationSelection(entries(), 'missing', 'company')).toBeUndefined()
    expect(businessNavigationSelection(entries(), 'finance', 'order')).toBeUndefined()
  })
  it('普通进入优先有文件对象，空应用不会遮挡其他应用', () => {
    const all = [{ key: 'empty', label: '空应用', spaces: [], loading: false }, ...entries()]
    expect(businessNavigationSelection(all, 'empty')).toEqual({ entryKey: 'finance', objectId: 'company' })
    expect(businessNavigationSelection(all, 'sales')).toEqual({ entryKey: 'sales', objectId: 'company' })
    expect(businessNavigationSelection([{ key: 'empty', label: '空应用', spaces: [], loading: false }])).toBeUndefined()
  })
})
