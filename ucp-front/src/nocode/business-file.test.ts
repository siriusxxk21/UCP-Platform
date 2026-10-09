import { describe, expect, it } from 'vitest'
import {
  appendBusinessFileId,
  BUSINESS_FILE_MAX_SIZE,
  canAppendBusinessFile,
  businessFileField,
  businessFileHint,
  businessSession,
  businessSessionId,
  forgetBusinessTempFile,
  loadBusinessSession,
  newBusinessSessionKey,
  rememberBusinessTempFile
} from './business-file'
import type { BusinessFilePolicy } from '@/types/nocode/data-center'

const policy = (overrides: Partial<BusinessFilePolicy> = {}): BusinessFilePolicy => ({
  spaceName: '经营资料',
  fixedPath: ['采购合同'],
  groups: [],
  recordLabelFields: [],
  fieldIds: ['f-file'],
  ...overrides
})

function memoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: key => data.get(key) ?? null,
    key: index => [...data.keys()][index] ?? null,
    removeItem: key => void data.delete(key),
    setItem: (key, value) => void data.set(key, value)
  }
}

describe('业务附件字段判定与提示', () => {
  it('只在规则启用且字段在参与清单内时进入业务模式', () => {
    expect(businessFileField(policy(), 'f-file')).toBe(true)
    expect(businessFileField(policy(), 'other')).toBe(false)
    expect(businessFileField(policy({ spaceName: '' }), 'f-file')).toBe(false)
    expect(businessFileField(policy({ fieldIds: [] }), 'f-file')).toBe(false)
    expect(businessFileField(null, 'f-file')).toBe(false)
    expect(businessFileField(policy(), null)).toBe(false)
  })
  it('位置提示只承诺已定前缀，动态目录标注保存时生成', () => {
    expect(businessFileHint(policy({ fixedPath: [] }))).toBe('保存后归入：经营资料')
    expect(businessFileHint(policy())).toBe('保存后归入：经营资料 / 采购合同')
    expect(businessFileHint(policy({ groups: [{ fieldId: 'f-project', format: null }] }))).toBe(
      '保存后归入：经营资料 / 采购合同 / …（其余目录保存时按记录生成）'
    )
    expect(businessFileHint(null)).toBe('')
  })
})

describe('业务附件上传边界', () => {
  it('精确接受 50MiB，超过一个字节即拒绝，字段最多保留 100 个文件', () => {
    expect(canAppendBusinessFile([], 0, BUSINESS_FILE_MAX_SIZE)).toBe(true)
    expect(canAppendBusinessFile([], 0, BUSINESS_FILE_MAX_SIZE + 1)).toBe(false)
    expect(
      canAppendBusinessFile(
        Array.from({ length: 99 }, (_, index) => String(index)),
        0,
        1
      )
    ).toBe(true)
    expect(
      canAppendBusinessFile(
        Array.from({ length: 99 }, (_, index) => String(index)),
        1,
        1
      )
    ).toBe(false)
  })

  it('并发完成时基于最新快照累计文件编号且自动去重', () => {
    const first = appendBusinessFileId([], 'file-a')
    const second = appendBusinessFileId(first, 'file-b')
    expect(second).toEqual(['file-a', 'file-b'])
    expect(appendBusinessFileId(second, 'file-a')).toEqual(second)
  })
})

describe('上传会话存储', () => {
  it('会话键符合服务端字符集约束，会话按账号与记录隔离且持久化', () => {
    const key = newBusinessSessionKey()
    expect(key).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
    const storage = memoryStorage()
    const id = businessSessionId('1001', 'app', 'object', null)
    const first = businessSession(storage, id)
    expect(businessSession(storage, id)).toEqual(first)
    rememberBusinessTempFile(storage, id, first, { fileId: '9', name: '合同.pdf', size: 10, fieldId: 'f-file' })
    expect(loadBusinessSession(storage, id)?.files['9']?.name).toBe('合同.pdf')
    forgetBusinessTempFile(storage, id, first, '9')
    expect(loadBusinessSession(storage, id)?.files).toEqual({})
    expect(businessSessionId('1001', 'app', 'object', 'r1')).not.toBe(id)
    expect(businessSessionId(null, null, 'object', 'r1')).toContain('anonymous:-:object:r1')
  })
  it('损坏的存储值与非法会话键按不存在处理，不抛错', () => {
    const storage = memoryStorage()
    storage.setItem('bad-json', '{')
    storage.setItem('bad-key', JSON.stringify({ key: '会话 1', files: {} }))
    expect(loadBusinessSession(storage, 'bad-json')).toBeNull()
    expect(loadBusinessSession(storage, 'bad-key')).toBeNull()
  })
})
