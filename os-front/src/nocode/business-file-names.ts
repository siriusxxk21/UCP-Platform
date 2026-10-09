import { getSimpleUserList } from '@/api/system/user'

let businessUserNames: Promise<Map<string, string>> | null = null
let businessUserNameScope = ''

/** 上传人只回填用户编号；按租户与登录账号隔离缓存，切换会话后不得复用旧租户姓名。 */
export function resolveBusinessUserNames(scope: string): Promise<Map<string, string>> {
  if (businessUserNameScope !== scope) {
    businessUserNameScope = scope
    businessUserNames = null
  }
  businessUserNames ||= getSimpleUserList()
    .then(list => new Map(list.map(user => [String(user.id), user.nickname || user.username || String(user.id)])))
    .catch(() => new Map<string, string>())
  return businessUserNames
}
