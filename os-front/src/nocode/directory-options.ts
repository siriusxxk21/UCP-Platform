import { getDepartmentTree } from '@/api/system/department'
import { getUserGroupSimpleList } from '@/api/bpm/userGroup'
import { getUsersByIds } from '@/api/system/user'
import request from '@/utils/request'
import { FieldType } from '@/types/nocode/enums'

export const directoryTypes = [FieldType.USER, FieldType.DEPARTMENT, FieldType.POST, FieldType.USER_GROUP]
/** 复用底座目录；人员仅按当前页引用批量读取，不拉取全部账号。 */
export async function directoryOptions(
  kind: FieldType,
  ids: string[] = []
): Promise<Array<{ value: string; label: string }>> {
  if (kind === FieldType.USER)
    return (ids.length ? await getUsersByIds(ids) : []).map(u => ({
      value: String(u.id),
      label: u.nickname || u.username
    }))
  if (kind === FieldType.DEPARTMENT) {
    const collect = (nodes: Awaited<ReturnType<typeof getDepartmentTree>>): Array<{ value: string; label: string }> =>
      (nodes || []).flatMap(d => [{ value: String(d.id), label: d.deptName }, ...collect(d.children || [])])
    return collect(await getDepartmentTree())
  }
  if (kind === FieldType.USER_GROUP)
    return (await getUserGroupSimpleList()).map(g => ({ value: String(g.id), label: g.name }))
  if (kind === FieldType.POST)
    return (await request.get<Array<{ id: string; name: string }>>('/system/post/list-all-simple')).map(p => ({
      value: String(p.id),
      label: p.name
    }))
  return []
}
