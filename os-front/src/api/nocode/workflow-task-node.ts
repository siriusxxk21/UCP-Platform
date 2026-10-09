import type { NocodeHttpClient } from './object'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'

/** 两端各自校验查看权限；知道关联编号不等于获得对端访问权。 */
export function createWorkflowTaskNodeApi(client: NocodeHttpClient) {
  const path = '/nocode/workflow/task-nodes'
  return {
    list: (processInstanceId: string) => client.get<WorkflowTaskNodeView[]>(path, { params: { processInstanceId } }),
    source: (taskId: string) => client.get<WorkflowTaskNodeView | null>(`${path}/source`, { params: { taskId } }),
    retry: (executionId: string) => client.post<WorkflowTaskNodeView>(`${path}/retry`, { executionId })
  }
}
