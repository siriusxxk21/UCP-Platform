import { reactive } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { countTightened, defaultObjectGrant, loadApplicationSharing, restrictObjectGrant } from './object-sharing'
import { newField } from './object-draft'
import { FieldType } from '@/types/nocode/enums'
import type { PublishedObject } from '@/types/nocode/application'
import {
  BusinessAction as A,
  RecordScope as S,
  type ApplicationMember,
  type ObjectGrant,
  type ObjectSharingGrant
} from '@/types/nocode/authorization'

const permission = (): ObjectGrant => ({
  objectId: '1',
  actions: [A.READ, A.UPDATE],
  scope: S.ALL,
  readFields: ['title', 'secret'],
  writeFields: ['title', 'secret'],
  readDetails: ['items'],
  writeDetails: ['items'],
  readRelations: ['links'],
  writeRelations: ['links']
})

const published = (versionNo: number, organizationName?: string): PublishedObject => ({
  objectId: '1',
  versionNo,
  checksum: `version-${versionNo}`,
  definition: {
    objectId: '1',
    objectCode: 'company',
    objectName: '公司',
    schemaName: 'public',
    tableName: 'biz_company',
    source: 'GENERATED',
    readOnly: false,
    titleFieldId: 'title',
    settings: { icon: null, ownerId: null, organizationId: null, titleTemplate: null },
    mainBinding: {
      source: 'GENERATED',
      schemaName: 'public',
      keyColumn: 'id',
      parentColumn: null,
      structureMode: 'MANAGED',
      readOnly: false,
      repairBaseFields: false,
      fingerprint: null
    },
    fieldOptions: {},
    fields: [
      { ...newField(0), id: 'title', name: '公司名称', type: FieldType.TEXT },
      ...(organizationName
        ? [{ ...newField(1), id: '15511', name: organizationName, type: FieldType.ORGANIZATION }]
        : [])
    ],
    details: [],
    relations: []
  }
})

const sharing = (): ObjectSharingGrant[] => [
  {
    objectId: '1',
    applicationId: 'app',
    applicationName: '公司管理',
    revision: 2,
    permission: { ...permission(), readFields: ['title', '15511'], writeFields: ['15511'] },
    reason: '授权所属组织',
    updater: '1',
    updateTime: ''
  }
]

describe('应用侧实时共享授权展示', () => {
  it('应用仍引用 V3 时，使用 V4 结构解析新增组织字段，不改应用引用和授权范围', async () => {
    const references = { '1': published(3) }
    const grants = sharing()
    const before = structuredClone({ references, grants })
    const api = {
      sharing: vi.fn().mockResolvedValue(grants),
      objectVersion: vi.fn().mockResolvedValue(published(4, '所属组织'))
    }
    const result = await loadApplicationSharing(api, 'app', references)
    const fields = result.objects['1']!.definition.fields
    const labels = result.grants[0]!.permission!.readFields.map(id => fields.find(field => field.id === id)?.name)
    expect(labels).toEqual(['公司名称', '所属组织'])
    expect(result.objects['1']!.versionNo).toBe(4)
    expect(result.unavailable).toEqual([])
    expect(api.objectVersion).toHaveBeenCalledWith('1')
    expect({ references, grants }).toEqual(before)
  })

  it('刷新同时更新权限和字段名称，撤销授权不会因读取结构重新出现', async () => {
    const api = {
      sharing: vi
        .fn()
        .mockResolvedValueOnce(sharing())
        .mockResolvedValueOnce([{ ...sharing()[0]!, permission: null }]),
      objectVersion: vi
        .fn()
        .mockResolvedValueOnce(published(4, '所属组织'))
        .mockResolvedValueOnce(published(5, '所属法人组织'))
    }
    await loadApplicationSharing(api, 'app', { '1': published(3) })
    const refreshed = await loadApplicationSharing(api, 'app', { '1': published(3) })
    expect(refreshed.objects['1']!.definition.fields.find(field => field.id === '15511')?.name).toBe('所属法人组织')
    expect(refreshed.grants[0]!.permission).toBeNull()
    expect(api.sharing).toHaveBeenCalledTimes(2)
    expect(api.objectVersion).toHaveBeenCalledTimes(2)
  })

  it('某个对象结构加载失败时明确标记，不用旧版本冒充当前范围，也不阻断其他对象', async () => {
    const other = { ...published(2), objectId: '2' }
    const api = {
      sharing: vi.fn().mockResolvedValue(sharing()),
      objectVersion: vi.fn().mockRejectedValueOnce(new Error('对象不可用')).mockResolvedValueOnce(other)
    }
    const result = await loadApplicationSharing(api, 'app', { '1': published(3), '2': other })
    expect(result.unavailable).toEqual(['1'])
    expect(result.objects['1']).toBeUndefined()
    expect(result.objects['2']?.versionNo).toBe(2)
    expect(result.grants).toEqual(sharing())
  })

  it('最新版覆盖重命名字段，同时保留历史授权引用的旧字段名称', async () => {
    const previous = published(3, '历史组织')
    const latest = published(4)
    latest.definition.fields[0]!.name = '正式名称'
    const api = { sharing: vi.fn().mockResolvedValue(sharing()), objectVersion: vi.fn().mockResolvedValue(latest) }
    const result = await loadApplicationSharing(api, 'app', { '1': previous })
    expect(result.objects['1']!.definition.fields.map(field => field.name)).toEqual(['正式名称', '历史组织'])
    expect(previous.definition.fields[0]!.name).toBe('公司名称')
  })
})

describe('对象共享授权编辑器', () => {
  it('将旧成员配置收紧到只读、本人记录及字段和关系上限，保留原配置供撤销编辑', () => {
    const member = permission()
    const upper = {
      ...permission(),
      actions: [A.READ],
      scope: S.OWN,
      readFields: ['title'],
      writeFields: [],
      readDetails: [],
      writeDetails: [],
      readRelations: [],
      writeRelations: []
    }
    expect(restrictObjectGrant(member, upper)).toEqual(upper)
    expect(member.writeFields).toEqual(['title', 'secret'])
  })
  it('对象授权较宽时也不扩大成员已有的本人记录范围', () => {
    const member = { ...permission(), scope: S.OWN, actions: [A.READ], writeFields: [] }
    expect(restrictObjectGrant(member, permission())).toEqual(member)
  })
  it('旧配置缺少关系集合时保持无授权', () => {
    const member = permission()
    delete member.readRelations
    delete member.writeRelations
    const result = restrictObjectGrant(member, permission())
    expect(result.readRelations).toEqual([])
    expect(result.writeRelations).toEqual([])
  })
})

it('收紧成员授权时保留其业务条件，不复制应用计算取数授权', () => {
  const grant = reactive(permission())
  grant.actionScopes = {
    READ: { logic: 'AND', conditions: [{ fieldId: 'title', operator: 'eq', value: 'A' }], groups: [] }
  }
  const ceiling = { ...permission(), computeFields: ['title'] }
  const narrowed = restrictObjectGrant(grant, ceiling)
  expect(narrowed.actionScopes).toEqual(grant.actionScopes)
  expect(narrowed.actionScopes).not.toBe(grant.actionScopes)
  expect(narrowed.computeFields).toBeUndefined()
})

describe('收紧成员授权时的「全部」', () => {
  const all = (): Partial<ObjectGrant> => ({
    readFields: ['*'],
    writeFields: ['*'],
    readDetails: ['*'],
    writeDetails: ['*'],
    readRelations: ['*'],
    writeRelations: ['*']
  })
  it('成员全部 + 上限清单 ⇒ 上限清单（不是全部）', () => {
    const upper = { ...permission(), readFields: ['title'], writeFields: [], readDetails: [], writeRelations: [] }
    const result = restrictObjectGrant({ ...permission(), ...all() }, upper)
    expect(result.readFields).toEqual(['title'])
    expect(result.writeFields).toEqual([])
    expect(result.readDetails).toEqual([])
    expect(result.writeDetails).toEqual(['items'])
    expect(result.readRelations).toEqual(['links'])
    expect(result.writeRelations).toEqual([])
  })
  it('成员清单 + 上限全部 ⇒ 成员清单', () => {
    const member = { ...permission(), readFields: ['secret'], writeFields: ['secret'] }
    const result = restrictObjectGrant(member, { ...permission(), ...all() })
    expect(result.readFields).toEqual(['secret'])
    expect(result.writeFields).toEqual(['secret'])
    expect(result.readDetails).toEqual(['items'])
    expect(result.writeRelations).toEqual(['links'])
  })
  it('两边全部 ⇒ 全部', () => {
    const result = restrictObjectGrant({ ...permission(), ...all() }, { ...permission(), ...all() })
    expect(result).toMatchObject(all())
  })
  it('清单是「全部」时照旧保留成员的记录条件', () => {
    const member: ObjectGrant = {
      ...permission(),
      ...all(),
      actionScopes: {
        READ: { logic: 'AND', conditions: [{ fieldId: 'title', operator: 'eq', value: 'A' }], groups: [] }
      }
    }
    const result = restrictObjectGrant(member, { ...permission(), ...all() })
    expect(result.actionScopes).toEqual(member.actionScopes)
    expect(result.actionScopes).not.toBe(member.actionScopes)
  })
})

describe('新建授权的默认值', () => {
  it('数据中心新增应用授权：六种操作、全部记录、六个清单都是全部（与系统自动写入的默认授权相同）', () => {
    expect(defaultObjectGrant('1', [A.READ, A.CREATE, A.UPDATE, A.DELETE, A.IMPORT, A.EXPORT], S.ALL)).toEqual({
      objectId: '1',
      actions: ['READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT'],
      scope: 'ALL',
      readFields: ['*'],
      writeFields: ['*'],
      readDetails: ['*'],
      writeDetails: ['*'],
      readRelations: ['*'],
      writeRelations: ['*']
    })
  })
  it('只有查看：可查看的三个全部，可修改的三个为空', () => {
    expect(defaultObjectGrant('1', [A.READ], S.OWN)).toEqual({
      objectId: '1',
      actions: ['READ'],
      scope: 'OWN',
      readFields: ['*'],
      writeFields: [],
      readDetails: ['*'],
      writeDetails: [],
      readRelations: ['*'],
      writeRelations: []
    })
  })
  it('有新增没有修改（直接填写）也给可修改全部', () => {
    const result = defaultObjectGrant('1', [A.READ, A.CREATE], S.OWN)
    expect([result.writeFields, result.writeDetails, result.writeRelations]).toEqual([['*'], ['*'], ['*']])
  })
  it('每次返回新对象和新数组，互不共用', () => {
    const actions = [A.READ, A.UPDATE]
    const first = defaultObjectGrant('1', actions, S.ALL),
      second = defaultObjectGrant('1', actions, S.ALL)
    expect(first.actions).not.toBe(actions)
    expect(first.readFields).not.toBe(second.readFields)
    expect(first.writeFields).not.toBe(first.writeDetails)
  })
})

describe('保存后被收紧的项数', () => {
  const member = (grant: Partial<ObjectGrant>, principalId = 'role'): ApplicationMember => ({
    principalKind: 'ROLE',
    principalId,
    objects: [{ ...permission(), ...grant }]
  })
  it('六个清单里提交了、返回里没有的项逐个计数', () => {
    const submitted = [member({})]
    const saved = [member({ readFields: ['title'], writeFields: [], writeRelations: [] })]
    expect(countTightened(submitted, saved)).toBe(4)
  })
  it('原样返回为 0', () => {
    expect(countTightened([member({})], [member({})])).toBe(0)
  })
  it('「全部」不逐项计数：提交全部或返回全部都不算收紧', () => {
    expect(countTightened([member({ readFields: ['*'] })], [member({ readFields: ['*'] })])).toBe(0)
    expect(countTightened([member({ readFields: ['*'] })], [member({ readFields: ['title'] })])).toBe(0)
    expect(countTightened([member({ readFields: ['title'] })], [member({ readFields: ['*'] })])).toBe(0)
  })
  it('按成员与对象对应，不把别的成员的清单拿来比', () => {
    const submitted = [member({}, 'a'), member({}, 'b')]
    const saved = [member({ readFields: [] }, 'b'), member({}, 'a')]
    expect(countTightened(submitted, saved)).toBe(2)
  })
  it('旧数据缺少关系清单时不报错', () => {
    const legacy = member({})
    for (const grant of legacy.objects) {
      delete grant.readRelations
      delete grant.writeRelations
    }
    expect(countTightened([legacy], [member({})])).toBe(0)
    expect(countTightened([member({})], [legacy])).toBe(2)
  })
})
