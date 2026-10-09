import { approvalDetail, workContext, legacyXml, simpleNode } from './fixtures'
import { legacyReviewItems, reviewApproval, reviewItems, reviewMaterial } from './review-fixtures'
let reviewStatus = 1,
  materialFailure = false
export function failNextMaterial() {
  materialFailure = true
}
const states = new Map<string, ReturnType<typeof workContext>>()
const clone = <T>(data: T): T => JSON.parse(JSON.stringify(data))
const context = (id: string) => {
  if (!states.has(id)) states.set(id, workContext(id === 'submitted'))
  return states.get(id)!
}
export default {
  async get(path: string, options?: any) {
    if (
      path.startsWith('/bpm/process-instance/get-approval-detail') &&
      options?.params?.processInstanceId === 'legacy-review-instance'
    ) {
      const result = reviewApproval(2)
      result.processInstance.id = 'legacy-review-instance'
      return clone(result)
    }
    if (
      path.startsWith('/bpm/process-instance/get-approval-detail') &&
      options?.params?.processInstanceId === 'review-instance'
    )
      return clone(reviewApproval(reviewStatus))
    if (path.startsWith('/bpm/process-instance/get-bpmn-model-view'))
      return { bpmnXml: legacyXml, simpleModel: simpleNode, tasks: [] }
    if (path.startsWith('/bpm/task/list-by-process-instance-id'))
      return clone(
        reviewItems
          .filter(item => item.state !== 'HISTORY')
          .map(item => ({
            id: item.taskId,
            name: item.nodeName,
            taskDefinitionKey: item.nodeId,
            createTime: item.submittedAt,
            endTime: item.submittedAt,
            status: 2,
            assigneeUser: { nickname: item.submitterName }
          }))
      )
    if (path.startsWith('/bpm/process-instance/get-approval-detail'))
      return clone(approvalDetail(context(options?.params?.taskId || 'editing').submission ? 'submitted' : 'editing'))
    throw new Error(`未配置模拟读取接口：${path}`)
  },
  async post(path: string, body: any) {
    if (path === '/nocode/flow-material/list' && body.processInstanceId === 'legacy-review-instance')
      return {
        items: clone(legacyReviewItems),
        reviewRequired: false,
        blockedReason: '部分材料暂不可查阅，请查看对应材料的具体说明'
      }
    if (path === '/nocode/flow-material/list')
      return {
        items: clone(reviewItems),
        reviewRequired: reviewStatus === 1,
        reviewToken: reviewStatus === 1 ? 'preview-review-token' : undefined
      }
    if (path === '/nocode/flow-material/detail') {
      if (materialFailure) {
        materialFailure = false
        throw new Error('模拟材料读取失败，请重试')
      }
      return clone(reviewMaterial(body.materialId))
    }
    const work = context(body.taskId)
    if (path === '/nocode/flow-task/open')
      return clone({ processInstanceId: 'instance-preview', nodeId: 'business_work', work })
    if (path === '/nocode/flow-task/draft/save') {
      work.draft.values = body.values
      work.draft.revision++
      work.draft.updatedAt = Date.now()
      return clone(work.draft)
    }
    if (path === '/nocode/flow-task/submit') {
      work.submission = { ...workContext(true).submission!, draftId: work.draft.id, values: clone(work.draft.values) }
      work.draft.state = 'SUBMITTED'
      work.writable = false
      return clone(work.submission)
    }
    if (path === '/nocode/flow-task/selection') return { items: [], before: null }
    throw new Error(`未配置模拟操作接口：${path}`)
  },
  async put(path: string, body: any) {
    if (path === '/bpm/task/approve') {
      if (body.materialReviewToken !== 'preview-review-token' || Object.keys(body.variables || {}).length)
        throw new Error('模拟校验：必须关联材料且不得回写历史字段')
      reviewStatus = 2
      return true
    }
    if (path === '/bpm/task/reject') {
      reviewStatus = 3
      return true
    }
    throw new Error('未配置模拟接口')
  }
}
