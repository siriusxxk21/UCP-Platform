import type { ObjectField } from '@/types/nocode/object'

/** 运行端只使用服务端已发布字段 ID；编辑草稿的临时 key 不能替代查询与权限标识。 */
export function hasRuntimeFieldId(field: ObjectField): field is ObjectField & { id: string } {
  return typeof field.id === 'string' && field.id.length > 0
}
