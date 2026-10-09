import type { LocationQuery, RouteLocationRaw } from 'vue-router'

const taskPages = new Set([
  '/nocode-app/task-center',
  '/nocode-app/task-center/manage',
  '/nocode-app/task-center/templates'
])

/** 旧发起地址只保留兼容跳转，模板及其他业务查询参数继续传递。 */
export function taskLaunchLocation(query: LocationQuery, hash = ''): RouteLocationRaw {
  return {
    path: '/nocode-app/task-center/manage',
    query: { ...(taskCenterRetiredQuery(query) || query), launch: '1' },
    hash
  }
}

/** 旧门户已退役；草稿、版本和记录参数属于旧业务表单，不能传给新版任务。 */
export function taskCenterRetiredQuery(query: LocationQuery): LocationQuery | null {
  if (!('legacy' in query) && !('entry' in query)) return null
  const current = { ...query }
  for (const key of ['legacy', 'entry', 'app', 'recordId', 'version', 'draftId']) delete current[key]
  return current
}

/** 正式任务页自行响应深链，消费一次性参数时不重建列表。 */
export function taskCenterViewKey(route: { path: string; fullPath: string; query: LocationQuery }): string {
  return taskPages.has(route.path) ? route.path : route.fullPath
}
