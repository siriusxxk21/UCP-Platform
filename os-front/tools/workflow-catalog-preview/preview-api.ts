let failNext = false
export const simulateFailure = () => {
  failNext = true
}
export const useUserStore = () => ({ userInfo: { id: 'preview-user' } })
const categories = [
  { id: 'admin', name: '行政管理', code: 'admin', status: 0, sort: 0 },
  { id: 'business', name: '业务办理', code: 'business', status: 0, sort: 1 },
  { id: 'empty', name: '新建空分类', code: 'empty', status: 0, sort: 2 }
]
const models = Array.from({ length: 27 }, (_, index) => ({
  id: `model-${index + 1}`,
  name: `${index < 15 ? '行政审批' : '业务登记'}流程 ${index + 1}`,
  key: `preview_workflow_${index + 1}`,
  category: index < 15 ? 'admin' : index === 26 ? 'legacy' : 'business',
  type: index % 2 ? 10 : 20,
  formType: index % 3 ? 10 : 0,
  formName: index % 3 ? '申请登记表' : undefined,
  managerUserIds: index === 25 ? ['other-user'] : ['preview-user'],
  processDefinition:
    index % 4
      ? { id: `definition-${index}`, version: 3, deploymentTime: 1789017600000, suspensionState: index === 3 ? 2 : 1 }
      : undefined
}))
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export async function getModelList() {
  if (failNext) {
    failNext = false
    throw new Error('模拟网络异常：点击重试恢复列表')
  }
  return clone(models)
}
export async function getCategorySimpleList() {
  return clone(categories)
}
export async function updateModelSortBatch(ids: string[]) {
  const ordered = ids.map(id => models.find(row => row.id === id)!).filter(Boolean)
  models.splice(0, models.length, ...ordered, ...models.filter(row => !ids.includes(row.id)))
}
export async function updateCategorySortBatch(ids: string[]) {
  const ordered = ids.map(id => categories.find(row => row.id === id)!).filter(Boolean)
  categories.splice(0, categories.length, ...ordered)
}
export async function deleteCategory(id: string) {
  const index = categories.findIndex(row => row.id === id)
  if (index >= 0) categories.splice(index, 1)
}
export async function cleanModel() {
  return true
}
export async function deleteModel(id: string) {
  const index = models.findIndex(row => row.id === id)
  if (index >= 0) models.splice(index, 1)
}
export async function deployModel() {
  return true
}
export async function updateModelState(id: string, state: number) {
  const model = models.find(row => row.id === id)
  if (model?.processDefinition) model.processDefinition.suspensionState = state
}

export const simpleModel = {
  id: 'StartUserNode',
  type: 10,
  name: '发起人',
  showText: '无需填写发起表单',
  childNode: {
    id: 'review',
    type: 11,
    name: '负责人审批',
    candidateStrategy: 30,
    candidateParam: '1',
    approveType: 1,
    approveMethod: 1,
    formBinding: { mode: 'INHERIT', taskMode: 'APPROVAL' },
    childNode: { id: 'EndEvent', type: 1, name: '结束' }
  }
}
let design: any = {
  id: 'preview-design',
  key: 'preview_optional_form',
  name: '发起表单可选验收',
  type: 20,
  formType: 0,
  category: 'admin',
  managerUserIds: ['preview-user'],
  simpleModel: JSON.stringify(simpleModel)
}
export const getModel = async () => clone(design)
export const createModel = async (data: any) => {
  design = clone(data)
  return 'preview-design'
}
export const updateModel = async (data: any) => {
  design = clone(data)
  return true
}
export const getFormSimpleList = async () => [{ id: '1', name: '申请登记表' }]
export const getSimpleUserList = async () => [
  { id: '1', nickname: '负责人' },
  { id: 'preview-user', nickname: '验收管理员' }
]
export const getUsersByIds = async () => getSimpleUserList()
export const getUserList = async () => ({ list: await getSimpleUserList(), total: 2 })
export const getOrgDeptTree = async () => []
export const getDepartmentTree = async () => []
export const getProcessDefinition = async () => ({ id: 'preview-definition', simpleModel: JSON.stringify(simpleModel) })
export const getApprovalDetail = async () => ({
  activityNodes: [{ id: 'review', name: '负责人审批', candidateUsers: [{ id: 1, nickname: '负责人' }] }]
})
export const createProcessInstance = async () => 'preview-instance'
