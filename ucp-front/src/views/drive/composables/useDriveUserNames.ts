import { ref } from 'vue'
import { getSimpleUserList } from '@/api/system/user'
import type { DriveId } from '@/types/drive'

/**
 * 节点接口只回填用户编号
 *
 * 上传人、删除人等字段在响应里是编号，列表要展示姓名只能自己换算；
 * 精简用户列表接口不校验业务权限，普通账号也能读取，因此按需一次性取回做映射。
 */
export function useDriveUserNames() {
  const names = ref(new Map<string, string>())

  async function loadUserNames() {
    try {
      const list = await getSimpleUserList()
      names.value = new Map(list.map(user => [String(user.id), user.nickname || user.username || String(user.id)]))
    } catch {
      names.value = new Map()
    }
  }

  function userName(id?: DriveId | string | null): string {
    if (id === null || id === undefined || id === '') return '-'
    return names.value.get(String(id)) || '-'
  }

  return { loadUserNames, userName }
}
