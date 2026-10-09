import { newTaskNode, taskNodeError } from './task-center'
import { taskNodeFromWire, taskNodeToWire } from './task-center-wire'
import { taskBusinessConfigError } from './task-work-rule'
import type { TaskTemplateVersion } from '@/types/nocode/task-center'
import type { WorkflowTaskNodeSetting, WorkflowTaskPerson } from '@/types/nocode/workflow-task-node'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))

export function newWorkflowTaskSetting(): WorkflowTaskNodeSetting {
  return {
    version: 1,
    source: 'CUSTOM',
    task: { ...newTaskNode(), dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } },
    nodes: [],
    people: []
  }
}

export function workflowTaskFromTemplate(template: TaskTemplateVersion): WorkflowTaskNodeSetting {
  const task = template.task ? { ...clone(template.task), parentId: null } : { ...newTaskNode(), title: template.name }
  return {
    version: 1,
    source: 'TEMPLATE',
    templateId: template.id,
    templateVersion: template.version,
    task,
    nodes: clone(template.nodes).map(node => ({ ...node, parentId: node.parentId || task.id })),
    people: []
  }
}

export function workflowTaskSettingError(setting?: WorkflowTaskNodeSetting): string | null {
  if (!setting || setting.version !== 1) return '请配置任务节点'
  if (!['CUSTOM', 'TEMPLATE'].includes(setting.source)) return '任务来源无效'
  if (setting.source === 'TEMPLATE' && (!setting.templateId || !setting.templateVersion))
    return '请选择任务模板和已发布版本'
  if (!setting.task || !Array.isArray(setting.nodes)) return '任务编排配置不完整'
  const nodes = [setting.task, ...setting.nodes.map(node => ({ ...node, parentId: node.parentId || setting.task.id }))]
  const seen = new Set<string>()
  for (const person of setting.people || []) {
    if (!nodes.some(node => node.id === person.nodeId)) return '人员来源引用的任务已不存在'
    if (!['ASSIGNEE', 'ACCEPTOR'].includes(person.role)) return '人员来源角色无效'
    if (person.role === 'ACCEPTOR' && person.nodeId !== setting.task.id) return '仅总任务可设置验收人'
    const key = `${person.nodeId}:${person.role}`
    if (seen.has(key)) return '同一任务的人员来源不能重复'
    seen.add(key)
    if (!['INITIATOR', 'FORM_FIELD'].includes(person.source)) return '请选择人员来源'
    if (person.source === 'FORM_FIELD' && !person.field?.trim()) return '请选择流程表单中的人员字段'
  }
  const assignee = setting.people?.find(p => p.nodeId === setting.task.id && p.role === 'ASSIGNEE')
  const acceptor = setting.people?.find(p => p.role === 'ACCEPTOR')
  if (assignee && acceptor && assignee.source === acceptor.source && assignee.field === acceptor.field)
    return '总任务负责人和验收人不能使用同一人员来源'
  // 仅本地校验使用占位；真实人员由运行时解析并校验，绝不写入流程配置。
  const validationNodes = nodes.map(node => {
    const copy = clone(node)
    if (setting.people?.some(p => p.nodeId === node.id && p.role === 'ASSIGNEE')) {
      copy.assignmentMode = 'ASSIGNED'
      copy.assigneeId = '__workflow_assignee__'
    }
    if (setting.people?.some(p => p.nodeId === node.id && p.role === 'ACCEPTOR'))
      copy.acceptorId = '__workflow_acceptor__'
    return copy
  })
  return taskNodeError(validationNodes) || taskBusinessConfigError(nodes) || null
}

export function setWorkflowTaskPerson(
  setting: WorkflowTaskNodeSetting,
  nodeId: string,
  role: WorkflowTaskPerson['role'],
  source?: WorkflowTaskPerson['source'],
  field?: string
) {
  setting.people = (setting.people || []).filter(p => p.nodeId !== nodeId || p.role !== role)
  const node = [setting.task, ...setting.nodes].find(item => item.id === nodeId)
  if (!node || !source) return
  setting.people.push({ nodeId, role, source, ...(source === 'FORM_FIELD' ? { field } : {}) })
  if (role === 'ASSIGNEE') {
    node.assignmentMode = 'ASSIGNED'
    node.assigneeId = null
    node.candidateUserIds = []
  } else node.acceptorId = null
}

export function workflowTaskFromWire(setting: WorkflowTaskNodeSetting): WorkflowTaskNodeSetting {
  return { ...setting, task: taskNodeFromWire(setting.task), nodes: setting.nodes.map(taskNodeFromWire) }
}
export function workflowTaskToWire(setting: WorkflowTaskNodeSetting) {
  return { ...setting, task: taskNodeToWire(setting.task), nodes: setting.nodes.map(taskNodeToWire) }
}
