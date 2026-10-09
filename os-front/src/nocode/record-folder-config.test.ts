import { describe, expect, it } from 'vitest'
import type { RecordFolderSource, RecordFolderSourceInput } from '@/types/nocode/record-folder'
import { recordFolderIssues, recordFolderSameConfig, recordFolderSaveInput } from './record-folder-config'

const folder = (value: Partial<RecordFolderSourceInput> = {}): RecordFolderSourceInput => ({
  kind: 'FOLDER',
  placement: 'RECORD_SUBFOLDER',
  label: '',
  spaceId: '900',
  entryId: '11',
  ...value
})
const relation = (value: Partial<RecordFolderSourceInput> = {}): RecordFolderSourceInput => ({
  kind: 'RELATION',
  placement: 'DIRECT',
  label: '',
  relationFieldId: 'f-contract',
  targetSourceId: '7',
  ...value
})
const template = (parts: NonNullable<RecordFolderSourceInput['nameTemplate']>['parts'], separator = '-') => ({
  separator,
  parts
})
const field = (fieldId: string) => ({ kind: 'FIELD' as const, fieldId })
const text = (value: string) => ({ kind: 'TEXT' as const, text: value })

describe('文件夹配置的前端校验镜像', () => {
  it('没有问题时返回空数组', () => {
    expect(
      recordFolderIssues([
        folder({ label: '合同文件', nameTemplate: template([field('f1'), text('资料')]) }),
        relation({ label: '合同文件夹' }),
        relation({ placement: 'RECORD_SUBFOLDER', label: '本凭证文件' })
      ])
    ).toEqual([])
  })

  it('C2：最多 6 个', () => {
    const six = Array.from({ length: 6 }, (_value, index) => folder({ entryId: String(index) }))
    expect(recordFolderIssues(six)).toEqual([])
    expect(recordFolderIssues([...six, folder({ entryId: '99' })])).toEqual(['一个对象最多配置 6 个文件夹'])
  })

  it('C4：页签名称去首尾空白后不超过 20 个字', () => {
    expect(recordFolderIssues([folder({ label: '  ' + '字'.repeat(20) + '  ' })])).toEqual([])
    expect(recordFolderIssues([folder({ label: '字'.repeat(21) })])).toEqual(['页签名称不能超过 20 个字'])
  })

  it('C4：非空的页签名称互不相同；空名称可以有多个', () => {
    expect(recordFolderIssues([folder({ label: '资料' }), relation({ label: ' 资料 ' })])).toEqual([
      '页签名称「资料」重复'
    ])
    expect(recordFolderIssues([folder({ label: '' }), relation({ label: '' })])).toEqual([])
  })

  it('C7：同一个文件夹同一种放法不能添加两次；放法不同可以', () => {
    expect(recordFolderIssues([folder(), folder()])).toEqual(['同一个文件夹同一种放法不能添加两次'])
    expect(recordFolderIssues([folder(), folder({ placement: 'DIRECT' })])).toEqual([])
    expect(recordFolderIssues([relation(), relation()])).toEqual(['同一个文件夹同一种放法不能添加两次'])
    expect(recordFolderIssues([relation(), relation({ targetSourceId: '8' })])).toEqual([])
    expect(recordFolderIssues([relation(), relation({ placement: 'RECORD_SUBFOLDER' })])).toEqual([])
  })

  it('C13：最多 5 段', () => {
    const five = template([field('a'), field('b'), field('c'), field('d'), text('末')])
    expect(recordFolderIssues([folder({ nameTemplate: five })])).toEqual([])
    const six = template([...five.parts, text('多')])
    expect(recordFolderIssues([folder({ nameTemplate: six })])).toEqual(['文件夹名称最多由 5 段组成'])
  })

  it('C13：至少要有一个字段', () => {
    expect(recordFolderIssues([folder({ nameTemplate: template([text('合同')]) })])).toEqual([
      '文件夹名称里至少要有一个字段'
    ])
    expect(recordFolderIssues([folder({ nameTemplate: template([]) })])).toEqual(['文件夹名称里至少要有一个字段'])
  })

  it('C13：分隔符只能是那五种', () => {
    for (const separator of ['-', '_', ' ', ' · ', ''])
      expect(recordFolderIssues([folder({ nameTemplate: template([field('a')], separator) })])).toEqual([])
    expect(recordFolderIssues([folder({ nameTemplate: template([field('a')], '/') })])).toEqual([
      '文件夹名称的分隔符不支持'
    ])
  })

  it('C13：固定文字 1 到 20 个字，不能含 / \\ 与换行、制表符', () => {
    const issue = '固定文字要在 1 到 20 个字之间，且不能包含 / 或 \\'
    const check = (value: string) => recordFolderIssues([folder({ nameTemplate: template([field('a'), text(value)]) })])
    expect(check('字'.repeat(20))).toEqual([])
    expect(check('  资料  ')).toEqual([])
    for (const bad of ['', '   ', '字'.repeat(21), 'a/b', 'a\\b', 'a\nb', 'a\rb', 'a\tb'])
      expect(check(bad)).toEqual([issue])
  })

  it('C14：名称里的字段不能重复', () => {
    expect(recordFolderIssues([folder({ nameTemplate: template([field('a'), field('a')]) })])).toEqual([
      '文件夹名称里的字段不能重复'
    ])
  })

  it('放法不是「子文件夹」的行不看名称组合', () => {
    expect(recordFolderIssues([folder({ placement: 'DIRECT', nameTemplate: template([text('只有文字')]) })])).toEqual(
      []
    )
  })

  it('同一句问题只报一次', () => {
    expect(recordFolderIssues([folder(), folder(), folder()])).toEqual(['同一个文件夹同一种放法不能添加两次'])
  })
})

describe('整形成提交体', () => {
  const stored: RecordFolderSource = {
    id: '7',
    objectId: 'obj',
    kind: 'FOLDER',
    placement: 'RECORD_SUBFOLDER',
    label: ' 合同文件 ',
    spaceId: '900',
    entryId: '11',
    createMode: 'ON_SAVE',
    nameTemplate: template([field('f1'), text(' 资料 ')]),
    displayLabel: '合同文件',
    folderPath: '业务档案 / 合同资料',
    problem: null
  }

  it('只留服务端接受的字段：回显字段不提交', () => {
    const [input] = recordFolderSaveInput([stored])
    expect(Object.keys(input).sort()).toEqual(
      [
        'createMode',
        'entryId',
        'id',
        'kind',
        'label',
        'nameTemplate',
        'placement',
        'relationFieldId',
        'spaceId',
        'targetSourceId'
      ].sort()
    )
    expect(input).toEqual({
      id: '7',
      kind: 'FOLDER',
      placement: 'RECORD_SUBFOLDER',
      label: '合同文件',
      spaceId: '900',
      entryId: '11',
      relationFieldId: null,
      targetSourceId: null,
      createMode: 'ON_SAVE',
      nameTemplate: {
        separator: '-',
        parts: [
          { kind: 'FIELD', fieldId: 'f1' },
          { kind: 'TEXT', text: '资料' }
        ]
      }
    })
  })

  it('放法切到「所有记录共用」：建立时机与名称组合被清掉', () => {
    const [input] = recordFolderSaveInput([{ ...stored, placement: 'DIRECT' }])
    expect(input.createMode).toBeNull()
    expect(input.nameTemplate).toBeNull()
  })

  it('关联来源切到「直接放进关联记录的文件夹」：同样清掉', () => {
    const [input] = recordFolderSaveInput([
      relation({ placement: 'DIRECT', createMode: 'ON_SAVE', nameTemplate: template([field('f1')]) })
    ])
    expect(input.createMode).toBeNull()
    expect(input.nameTemplate).toBeNull()
    expect(input).toMatchObject({ spaceId: null, entryId: null, relationFieldId: 'f-contract', targetSourceId: '7' })
  })

  it('子文件夹放法：没选建立时机按「第一次放东西时建」，没有名称组合就是用记录名称', () => {
    const [input] = recordFolderSaveInput([folder()])
    expect(input.createMode).toBe('ON_FIRST_WRITE')
    expect(input.nameTemplate).toBeNull()
    expect(input.id).toBeNull()
  })

  it('顺序与入参一致', () => {
    expect(recordFolderSaveInput([relation({ label: '乙' }), folder({ label: '甲' })]).map(item => item.label)).toEqual(
      ['乙', '甲']
    )
  })

  it('有没有未保存的修改：只比提交出去的内容', () => {
    expect(recordFolderSameConfig([stored], [{ ...stored, displayLabel: '别的回显', problem: '配置已失效' }])).toBe(
      true
    )
    expect(recordFolderSameConfig([stored], [{ ...stored, label: '改了' }])).toBe(false)
    expect(recordFolderSameConfig([stored], [stored, folder()])).toBe(false)
    // 放法不是子文件夹时，底下藏着的建立时机不算修改
    const direct = { ...stored, placement: 'DIRECT' as const }
    expect(recordFolderSameConfig([direct], [{ ...direct, createMode: 'ON_FIRST_WRITE' }])).toBe(true)
  })
})
