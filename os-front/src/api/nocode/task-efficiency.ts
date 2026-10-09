import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type * as T from '@/types/nocode/task-efficiency'

export function createTaskEfficiencyApi(client: NocodeHttpClient) {
  const root = '/nocode/tasks/efficiency'
  return {
    efficiencyOverview: (query: T.TaskEfficiencyQuery) =>
      client.post<T.TaskEfficiencyOverview>(`${root}/overview`, query),
    efficiencyEmployees: (query: T.TaskEfficiencyQuery) =>
      client.post<Page<T.TaskEfficiencyEmployee>>(`${root}/employees`, query),
    efficiencyTasks: (query: T.TaskEfficiencyQuery) => client.post<Page<T.TaskEfficiencyTask>>(`${root}/tasks`, query),
    efficiencyRecords: (query: T.TaskEfficiencyQuery) =>
      client.post<Page<T.TaskEfficiencyRecord>>(`${root}/records`, query),
    efficiencyOptions: (query: T.TaskEfficiencyQuery) => client.post<T.TaskEfficiencyOptions>(`${root}/options`, query)
  }
}
export type TaskEfficiencyApi = ReturnType<typeof createTaskEfficiencyApi>
