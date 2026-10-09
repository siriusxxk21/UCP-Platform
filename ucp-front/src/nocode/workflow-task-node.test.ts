import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import { newTaskNode } from './task-center'
import {
  newWorkflowTaskSetting,
  workflowTaskFromTemplate,
  workflowTaskSettingError,
  setWorkflowTaskPerson,
  workflowTaskToWire,
  workflowTaskFromWire
} from './workflow-task-node'
import type { TaskTemplateVersion } from '@/types/nocode/task-center'
import type { WorkflowTaskNodeSetting } from '@/types/nocode/workflow-task-node'

function configured() {
  const setting = newWorkflowTaskSetting()
  setting.task.title = '办公室装修'
  return setting
}
describe('工作流任务节点配置', () => {
  it('默认配置只含任务草稿，不包含实例或计划，并要求填写任务名称', () => {
    const setting = newWorkflowTaskSetting()
    expect(setting.source).toBe('CUSTOM')
    expect(setting.people).toEqual([])
    expect(setting.task.assignmentMode).toBe('OPEN')
    expect(workflowTaskSettingError(setting)).toContain('名称')
    expect(workflowTaskSettingError(configured())).toBeNull()
  })
  it('模板读取形成独立且固定版本的副本，兼容顶层子任务空parentId', () => {
    const root = newTaskNode(),
      child = { ...newTaskNode(), title: '施工' }
    root.title = '装修'
    const template = { id: 'template', name: '装修模板', version: 2, task: root, nodes: [child] } as TaskTemplateVersion
    const setting = workflowTaskFromTemplate(template)
    expect(setting.templateVersion).toBe(2)
    expect(setting.nodes[0]?.parentId).toBe(root.id)
    setting.task.title = '流程专属装修'
    expect(template.task?.title).toBe('装修')
    expect(template.nodes[0]?.parentId).toBeNull()
    expect(workflowTaskSettingError(setting)).toBeNull()
  })
  it('旧模板缺根配置时补独立总任务，不把第一个步骤当总任务', () => {
    const child = { ...newTaskNode(), title: '步骤' }
    const setting = workflowTaskFromTemplate({
      id: 'old',
      name: '旧模板',
      version: 1,
      task: null,
      nodes: [child]
    } as TaskTemplateVersion)
    expect(setting.task.title).toBe('旧模板')
    expect(setting.task.id).not.toBe(child.id)
    expect(setting.nodes[0]?.parentId).toBe(setting.task.id)
  })
  it('模板来源必须有明确已发布版本', () => {
    const setting = configured()
    setting.source = 'TEMPLATE'
    setting.templateId = 'template'
    expect(workflowTaskSettingError(setting)).toContain('版本')
  })
  it('动态人员清理固定人员，校验占位不能污染原始配置或响应式对象', () => {
    const setting = reactive(configured())
    setting.task.assigneeId = '100'
    setting.task.candidateUserIds = ['200']
    setWorkflowTaskPerson(setting, setting.task.id, 'ASSIGNEE', 'INITIATOR')
    setWorkflowTaskPerson(setting, setting.task.id, 'ACCEPTOR', 'FORM_FIELD', 'reviewer')
    expect(workflowTaskSettingError(setting)).toBeNull()
    expect(setting.task.assigneeId).toBeNull()
    expect(setting.task.acceptorId).toBeNull()
    expect(setting.task.candidateUserIds).toEqual([])
    expect(setting.task.assignmentMode).toBe('ASSIGNED')
    expect(JSON.stringify(setting)).not.toContain('__workflow_')
  })
  it('总负责人和验收人同一动态来源禁止保存，人员字段必须选择', () => {
    const setting = configured()
    setWorkflowTaskPerson(setting, setting.task.id, 'ASSIGNEE', 'INITIATOR')
    setWorkflowTaskPerson(setting, setting.task.id, 'ACCEPTOR', 'INITIATOR')
    expect(workflowTaskSettingError(setting)).toContain('同一人员来源')
    setWorkflowTaskPerson(setting, setting.task.id, 'ACCEPTOR', 'FORM_FIELD')
    expect(workflowTaskSettingError(setting)).toContain('人员字段')
  })
  it('拒绝不存在的任务、子任务验收人与重复来源配置', () => {
    const setting = configured()
    const child = { ...newTaskNode(setting.task.id), title: '施工' }
    setting.nodes.push(child)
    setting.people = [{ nodeId: 'missing', role: 'ASSIGNEE', source: 'INITIATOR' }]
    expect(workflowTaskSettingError(setting)).toContain('不存在')
    setting.people = [{ nodeId: child.id, role: 'ACCEPTOR', source: 'INITIATOR' }]
    expect(workflowTaskSettingError(setting)).toContain('仅总任务')
    const person = { nodeId: child.id, role: 'ASSIGNEE', source: 'INITIATOR' } as const
    setting.people = [person, person]
    expect(workflowTaskSettingError(setting)).toContain('不能重复')
  })
  it('清除动态来源后保留用户新选择的固定人员', () => {
    const setting = configured()
    setWorkflowTaskPerson(setting, setting.task.id, 'ASSIGNEE', 'INITIATOR')
    setting.task.assigneeId = '9007199254740999'
    setWorkflowTaskPerson(setting, setting.task.id, 'ASSIGNEE')
    expect(setting.people).toEqual([])
    expect(setting.task.assigneeId).toBe('9007199254740999')
  })
  it('发布时日期按任务协议传毫秒时间戳，回显不丢时分秒', () => {
    const setting = configured()
    setting.task.schedule = {
      mode: 'FIXED',
      fixedStart: '2026-10-06T09:30:00',
      fixedEnd: '2026-10-07',
      offsetDays: 0,
      durationDays: 1
    }
    const wire = workflowTaskToWire(setting)
    expect(typeof wire.task.schedule.fixedStart).toBe('number')
    const restored = workflowTaskFromWire(wire as unknown as WorkflowTaskNodeSetting)
    expect(restored.task.schedule.fixedStart).toBe('2026-10-06T09:30:00')
    expect(restored.task.schedule.fixedEnd).toBe('2026-10-07T23:59:59.999')
    expect(setting.task.schedule.fixedEnd).toBe('2026-10-07')
  })
})
