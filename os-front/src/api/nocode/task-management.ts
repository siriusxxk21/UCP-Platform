import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type { TaskRow } from '@/types/nocode/task-center'
import type {
  TaskEmployeeOverviewQuery,
  TaskEmployeeOverviewRow,
  TaskManagementQuery
} from '@/types/nocode/task-management'
import { taskRowFromWire } from '@/nocode/task-center-wire'

export function createTaskManagementApi(client: NocodeHttpClient) {
  const root = '/nocode/tasks/management'
  return {
    managementEmployees: (query: TaskEmployeeOverviewQuery) =>
      client.post<Page<TaskEmployeeOverviewRow>>(`${root}/employees`, query),
    managementPage: (body: TaskManagementQuery) =>
      client.post<Page<TaskRow>>(`${root}/page`, body).then(page => ({
        ...page,
        list: page.list.map(taskRowFromWire)
      }))
  }
}
export type TaskManagementApi = ReturnType<typeof createTaskManagementApi>
