import type { TaskNodeInput, TaskState } from './task-center'

/** 配置只保存在流程定义；流程抵达节点后才由服务端创建真实任务。 */
export interface WorkflowTaskPerson {
  nodeId: string
  role: 'ASSIGNEE' | 'ACCEPTOR'
  source: 'INITIATOR' | 'FORM_FIELD'
  field?: string
}
export interface WorkflowTaskNodeSetting {
  version: 1
  source: 'TEMPLATE' | 'CUSTOM'
  templateId?: string
  templateVersion?: number
  task: TaskNodeInput
  nodes: TaskNodeInput[]
  people: WorkflowTaskPerson[]
}

export interface WorkflowTaskNodeView {
  readOnlyReason?: string | null
  executionId: string
  processInstanceId: string
  nodeId: string
  nodeName: string
  taskId?: string | null
  state: 'CREATING' | 'WAITING' | 'COMPLETED' | 'INVALIDATED'
  taskState?: TaskState | null
  error?: string | null
  canViewTask: boolean
  canViewProcess: boolean
  canRetry: boolean
  createdAt: string | number
}
