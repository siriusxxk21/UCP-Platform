import type { NocodeHttpClient } from './object'
import type { TaskWorkTimeChange, TaskWorkTimeContext } from '@/types/nocode/task-work-time'

export function createTaskWorkTimeApi(client: NocodeHttpClient) {
  return {
    workTimeContext: (id: string) => client.post<TaskWorkTimeContext>('/nocode/tasks/work-time/context', { id }),
    adjustWorkTime: (body: TaskWorkTimeChange) =>
      client.post<TaskWorkTimeContext>('/nocode/tasks/work-time/adjust', body)
  }
}
