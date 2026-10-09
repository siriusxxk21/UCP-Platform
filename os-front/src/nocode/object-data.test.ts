import { describe, expect, it } from 'vitest'
import {
  changedObjectData,
  objectDataColumnChoices,
  objectDataColumnRequest,
  objectDataInput,
  objectDataValue
} from './object-data'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { ObjectDataModel } from '@/types/nocode/object-data'

const field = (type: ObjectField['type'], id = 'value'): ObjectField => ({
  key: id,
  id,
  code: id,
  name: id,
  type,
  length: null,
  precision: null,
  scale: null,
  required: false,
  unique: false,
  sort: 0
})

describe('整列维护范围', () => {
  const model = {
    versionNo: 2,
    checksum: 'published-2',
    model: {
      object: {
        fields: [field(FieldType.TEXT, 'main')],
        details: [
          { id: 'detail', name: '采购明细', state: 'ACTIVE', fields: [field(FieldType.DECIMAL, 'line')] },
          { id: 'disabled', name: '停用明细', state: 'DISABLED', fields: [field(FieldType.TEXT, 'old')] }
        ]
      }
    }
  } as unknown as ObjectDataModel

  it('准确定位主表和活动明细列，不把已停用明细暴露成当前数据', () => {
    expect(objectDataColumnChoices(model).map(item => item.value)).toEqual(['main', 'line'])
    expect(objectDataColumnRequest('object', model, 'line')).toEqual({
      objectId: 'object',
      versionNo: 2,
      checksum: 'published-2',
      fieldId: 'line',
      detailId: 'detail'
    })
  })

  it('主表清空只提交当前发布结构与列，不能夹带记录过滤或删除行', () => {
    expect(objectDataColumnRequest('object', model, 'main')).toEqual({
      objectId: 'object',
      versionNo: 2,
      checksum: 'published-2',
      fieldId: 'main'
    })
    expect(() => objectDataColumnRequest('object', model, 'old')).toThrow('当前已发布结构中已找不到此列')
  })
})

describe('对象数据网格值协议', () => {
  it('大整数和高精度小数不经过浮点数转换', () => {
    const number = field(FieldType.DECIMAL)
    const value = '9007199254740993.12345678'
    expect(objectDataInput(number, value)).toBe(value)
    expect(objectDataValue(number, value)).toBe(value)
  })

  it('布尔和多值恢复业务值而不是提交字符串', () => {
    expect(objectDataValue(field(FieldType.BOOLEAN), 'false')).toBe(false)
    expect(objectDataValue(field(FieldType.MULTI_SELECT), '["a","b"]')).toEqual(['a', 'b'])
    expect(objectDataValue(field(FieldType.TEXT), null)).toBeNull()
  })

  it('更新仅提交改动列，不覆盖原有明细或非编辑字段', () => {
    const fields = [field(FieldType.TEXT, 'name'), field(FieldType.INTEGER, 'quantity')]
    expect(
      changedObjectData(
        fields,
        { name: '原名', quantity: '12', formula: '24' },
        { name: '新名', quantity: '12' },
        false
      )
    ).toEqual({ name: '新名' })
    expect(changedObjectData(fields, { name: '原名' }, { name: null }, false)).toEqual({ name: null })
  })
})
