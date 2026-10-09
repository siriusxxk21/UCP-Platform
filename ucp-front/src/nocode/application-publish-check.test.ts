import { describe, expect, it } from 'vitest'
import { collectPublishSharingIssues } from './application-publish-check'
import type { ApplicationDefinition, PublishedObject } from '@/types/nocode/application'
import type { ObjectSharingGrant } from '@/types/nocode/authorization'

const objects = {
  '4442': { objectId: '4442', definition: { objectName: '资产分类', fields: [{ id: 'code', name: '分类编号' }] } },
  '4443': { objectId: '4443', definition: { objectName: '资产台账', fields: [] } }
} as unknown as Record<string, PublishedObject>
const definition: ApplicationDefinition = {
  objects: [
    { objectId: '4442', versionNo: 1, checksum: 'a' },
    { objectId: '4443', versionNo: 1, checksum: 'b' }
  ],
  resources: [
    {
      id: 'number',
      kind: 'NUMBER_RULE',
      code: 'number',
      name: '分类自动编号',
      config: { objectId: '4442', fieldId: 'code' }
    }
  ]
}
function grant(objectId = '4442'): ObjectSharingGrant {
  return {
    objectId,
    applicationId: 'app',
    applicationName: '资产管理',
    revision: 1,
    reason: '',
    updater: '',
    updateTime: '',
    permission: {
      objectId,
      actions: ['READ', 'CREATE'],
      scope: 'OWN',
      readFields: ['code'],
      writeFields: ['code'],
      readDetails: [],
      writeDetails: []
    }
  }
}
const check = (grants: ObjectSharingGrant[]) =>
  collectPublishSharingIssues('app', '资产管理', definition, objects, grants)

describe('发布共享授权缺项提示', () => {
  it('一次列出全部未授权对象，显示业务名称、当前应用和补配步骤', () => {
    const result = check([])
    expect(result.map(issue => issue.objectName)).toEqual(['资产分类', '资产台账'])
    expect(result[0]?.problem).toContain('尚未配置授予应用“资产管理”')
    expect(result[0]?.fix).toContain('新增应用授权，选择“资产管理”')
    expect(result[0]?.fix).toContain('可查看字段')
  })
  it('明确区分已撤销，不能把撤销记录当作有效授权', () => {
    const result = check([{ ...grant(), permission: null }, grant('4443')])
    expect(result).toHaveLength(1)
    expect(result[0]?.problem).toContain('共享授权已被撤销')
    expect(result[0]?.fix).toContain('重新授权')
  })
  it('编号权限不足时列出规则、具体字段和全部缺失权限', () => {
    const limited = grant()
    limited.permission!.actions = ['READ']
    limited.permission!.writeFields = []
    const result = check([limited, grant('4443')])
    expect(result).toHaveLength(1)
    expect(result[0]?.problem).toBe('自动编号“分类自动编号”缺少“新增”操作权限、“分类编号”字段的填写和修改权限。')
    expect(result[0]?.fix).toContain('编辑授予应用“资产管理”的共享授权')
  })
  it('已有新增权限时只提示字段权限，避免误导扩大操作权限', () => {
    const limited = grant()
    limited.permission!.writeFields = []
    const [issue] = check([limited, grant('4443')])
    expect(issue?.problem).toContain('“分类编号”字段的填写和修改权限')
    expect(issue?.problem).not.toContain('“新增”')
  })
  it('不把其他应用的授权当作当前应用的授权', () => {
    expect(check([{ ...grant(), applicationId: 'other' }])).toHaveLength(2)
  })
  it('授权补齐后清除问题，允许仅本人记录范围，不改变授权数据', () => {
    const grants = [grant(), grant('4443')]
    const before = structuredClone(grants)
    expect(check(grants)).toEqual([])
    expect(grants).toEqual(before)
  })
  it('对象名称读取失败时明确标注状态，仍报告缺项', () => {
    const result = collectPublishSharingIssues('app', '资产管理', definition, {}, [])
    expect(result).toHaveLength(2)
    expect(result[0]?.objectName).toBe('名称暂无法读取（对象编号 4442）')
  })
  it('上限的可填写字段是「全部」时，业务编号字段不报缺少填写权限', () => {
    const all = grant()
    Object.assign(all.permission ?? {}, { readFields: ['*'], writeFields: ['*'] })
    expect(check([all, grant('4443')])).toEqual([])
  })
  it('「全部」与具体项混用不当作全部（那是坏数据，照常提示缺字段权限）', () => {
    const mixed = grant()
    Object.assign(mixed.permission ?? {}, { writeFields: ['*', 'other'] })
    const [issue] = check([mixed, grant('4443')])
    expect(issue?.problem).toContain('“分类编号”字段的填写和修改权限')
  })
})
