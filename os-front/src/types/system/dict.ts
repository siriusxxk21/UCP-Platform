/** 系统字典沿用底座状态编码：0 启用，1 禁用。大整数主键保持字符串。 */
export type DictStatus = 0 | 1
export type DictId = string | number

export interface DictTypeSave {
  id?: DictId
  name: string
  type: string
  status: DictStatus
  remark?: string
}

export interface DictType extends DictTypeSave {
  id: DictId
  createTime: string
}

export interface DictDataSave {
  id?: DictId
  dictType: string
  label: string
  value: string
  sort: number
  status: DictStatus
  colorType?: string
  cssClass?: string
  remark?: string
}

export interface DictData extends DictDataSave {
  id: DictId
  createTime: string
}

export interface DictTypeQuery {
  name?: string
  type?: string
  status?: DictStatus
}

export interface DictDataQuery {
  dictType: string
  label?: string
  status?: DictStatus
}

export const DICT_STATUS_OPTIONS = [
  { label: '启用', value: 0 },
  { label: '禁用', value: 1 }
]

export const DICT_TAG_MAP = {
  status: {
    0: { label: '启用', color: 'success' },
    1: { label: '禁用', color: 'error' }
  }
}

/** 存储兼容既有颜色编码，展示使用当前 Ant Design 标签样式。 */
export const DICT_COLOR_OPTIONS = [
  { label: '默认', value: 'default', color: 'default' },
  { label: '主要', value: 'primary', color: 'processing' },
  { label: '成功', value: 'success', color: 'success' },
  { label: '信息', value: 'info', color: 'default' },
  { label: '警告', value: 'warning', color: 'warning' },
  { label: '危险', value: 'danger', color: 'error' }
]

export function dictTagColor(colorType?: string) {
  return DICT_COLOR_OPTIONS.find(option => option.value === colorType)?.color || 'default'
}
