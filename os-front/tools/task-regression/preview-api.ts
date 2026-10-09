/** 仅由独立视觉回归配置加载，所有数据均为内存夹具，不连接开发库。 */
let failNext = false
export function simulateFailure() {
  failNext = true
}
const rows = Array.from({ length: 23 }, (_, index) => ({
  id: `preview-task-${index + 1}`,
  name: index % 2 ? '设备安装登记' : '现场勘查登记',
  status: [1, 2, 3, 4, 5][index % 5],
  withdrawable: index % 10 === 1,
  reason: index % 2 ? '材料齐全，按计划完成。' : '',
  createTime: '2026-09-10T10:15:00',
  endTime: index % 2 ? '2026-09-10T10:20:00' : undefined,
  durationInMillis: index % 2 ? 300000 : undefined,
  processInstanceId: `preview-process-${index + 1}`,
  processInstanceName: `项目设备安装与验收 · ${index + 1}`,
  processInstanceStartTime: '2026-09-09T09:00:00',
  processInstance: {
    id: `preview-process-${index + 1}`,
    name: `项目设备安装与验收 · ${index + 1}`,
    createTime: '2026-09-09T09:00:00',
    startUser: { nickname: '测试发起人' },
    summary: [{ key: '设备', value: '楼宇控制器' }]
  },
  assigneeUser: { nickname: '测试办理人' },
  startUser: { nickname: '测试发起人' },
  createUser: { nickname: '测试抄送人' },
  activityId: 'preview-node',
  activityName: '设备安装',
  summary: [{ key: '设备', value: '楼宇控制器' }]
}))
async function page(params: Record<string, any>) {
  if (failNext) {
    failNext = false
    throw new Error('模拟服务不可用，请点击重试。')
  }
  const selected = rows.filter(
    row =>
      (!params.name || row.name.includes(params.name)) &&
      (!params.processInstanceName || row.processInstanceName.includes(params.processInstanceName)) &&
      (params.status === undefined || row.status === params.status)
  )
  return {
    list: selected.slice((params.pageNo - 1) * params.pageSize, params.pageNo * params.pageSize),
    total: selected.length
  }
}
export const getTaskTodoPage = page
export const getTaskDonePage = page
export const getTaskManagerPage = page
export const getProcessInstanceCopyPage = page
export async function withdrawTask() {
  return true
}
export async function getCategorySimpleList() {
  return [{ code: 'work', name: '工程工作' }]
}
export async function getSimpleProcessDefinitionList() {
  return [{ key: 'demo', name: '项目设备安装与验收' }]
}

export default {
  async post(path: string, params: { state: string; before: unknown }) {
    if (path !== '/nocode/flow-task/work-page') throw new Error('视觉回归不支持此接口')
    if (failNext) {
      failNext = false
      throw new Error('模拟服务不可用，请点击重试。')
    }
    const submitted = params.state === 'SUBMITTED'
    const offset = params.before ? 2 : 0
    return {
      items: Array.from({ length: params.before ? 1 : 2 }, (_, index) => {
        const id = offset + index + 1
        const writable = !submitted && id !== 2
        return {
          draftId: `preview-draft-${params.state}-${id}`,
          taskId: `preview-task-${id}`,
          processInstanceId: `preview-process-${id}`,
          nodeId: 'preview-node',
          state: params.state,
          formName: id % 2 ? '现场勘查登记' : '设备安装登记',
          objectName: '工程服务单',
          applicationId: 'preview-application',
          applicationVersion: id === 3 ? 2 : 1,
          updatedAt: '2026-09-10T10:15:00',
          submissionId: submitted ? `preview-submission-${id}` : null,
          writable,
          blockedReason: writable || submitted ? null : '当前没有新增工程服务单的权限'
        }
      }),
      before: params.before ? null : { createdAt: '2026-09-10T10:15:00.123456', id: 'preview-cursor-2' }
    }
  }
}
