import type { FlowMaterialDetail, FlowMaterialItem } from '@/api/nocode/flow-material'
import { defaultFormNodes } from '@/nocode/form-presentation'
import type { ObjectField } from '@/types/nocode/object'
import { workContext } from './fixtures'
export const reviewItems: FlowMaterialItem[] = [
  {
    id: 'company-material',
    taskId: 'task-company',
    nodeId: 'company',
    nodeName: '公司信息登记',
    formName: '公司登记表',
    submitterName: '张三',
    submittedAt: '2026-09-10 09:20:00',
    revision: 1,
    kind: 'APPLICATION_FORM',
    state: 'CURRENT',
    required: true
  },
  {
    id: 'contract-material',
    taskId: 'task-contract',
    nodeId: 'contract',
    nodeName: '合同信息补充',
    formName: '服务合同表',
    submitterName: '李四',
    submittedAt: '2026-09-10 10:10:00',
    revision: 2,
    kind: 'APPLICATION_FORM',
    state: 'CURRENT',
    required: true
  },
  {
    id: 'finance-material',
    taskId: 'task-finance',
    nodeId: 'finance',
    nodeName: '财务核验',
    formName: '财务核验表',
    submitterName: '王五',
    submittedAt: '2026-09-10 10:45:00',
    revision: 1,
    kind: 'FLOW_FORM',
    state: 'CURRENT',
    required: true
  },
  {
    id: 'contract-history',
    taskId: 'task-contract-history',
    nodeId: 'contract',
    nodeName: '合同信息补充',
    formName: '服务合同表',
    submitterName: '李四',
    submittedAt: '2026-09-10 09:50:00',
    revision: 1,
    kind: 'APPLICATION_FORM',
    state: 'HISTORY',
    required: false
  }
]
export const legacyReviewItems: FlowMaterialItem[] = [
  {
    ...reviewItems[0]!,
    id: 'missing-start',
    taskId: 'START:legacy-review-instance',
    nodeId: 'start',
    nodeName: '发起申请',
    formName: '发起表单',
    kind: 'FLOW_FORM',
    state: 'UNAVAILABLE',
    warning: '历史发起表单未封存初始提交值，不能以当前流程变量代替历史材料'
  },
  reviewItems[0]!
]
function business(item: FlowMaterialItem, fieldsAndValues: [string, string][]): FlowMaterialDetail {
  const model = workContext().model
  const fields: ObjectField[] = fieldsAndValues.map(([name], index) => ({
    id: `field_${index}`,
    code: `c_field_${index}`,
    type: 'TEXT',
    name
  }))
  model.object = { ...model.object, objectName: item.formName, fields }
  model.permissions.readFields = fields.map(field => field.id!)
  return {
    item,
    businessForm: {
      applicationId: 'app-preview',
      form: { objectId: 'company', detailIds: [], nodes: defaultFormNodes(fields), options: { layout: 'horizontal' } },
      model,
      values: Object.fromEntries(fieldsAndValues.map(([, value], index) => [`field_${index}`, value]))
    }
  }
}
export function reviewMaterial(id: string): FlowMaterialDetail {
  const item = reviewItems.find(item => item.id === id)!
  if (id === 'company-material')
    return business(item, [
      ['公司名称', '上海示例科技有限公司'],
      ['统一社会信用代码', '91310000MA1ABCDE12X'],
      ['法定代表人', '王明'],
      ['注册资本', '500 万元'],
      ['所属行业', '软件和信息技术服务业'],
      ['联系人', '李华'],
      ['注册地址', '上海市浦东新区科创路 100 号']
    ])
  if (id.startsWith('contract'))
    return business(item, [
      ['合同名称', '年度技术服务合同'],
      ['合同编号', 'HT-2026-0910'],
      ['合同金额', id === 'contract-history' ? '100,000.00 元' : '120,000.00 元'],
      ['合同期限', '2026-10-01 至 2027-09-30'],
      ['付款方式', '按季度支付'],
      ['业务说明', '为公司提供系统运维与技术支持']
    ])
  return {
    item,
    flowForm: {
      conf: '{"form":{"layout":"vertical"}}',
      fields: [
        '{"type":"input","field":"budget","title":"预算确认"}',
        '{"type":"input","field":"payee","title":"付款安排"}'
      ],
      values: { budget: '预算已确认', payee: '按合同季度支付' }
    }
  }
}
export function reviewApproval(status = 1) {
  return {
    processInstance: {
      id: 'review-instance',
      name: '公司登记闭环审批',
      status,
      endTime: status === 1 ? null : '2026-09-10 11:30:00',
      createTime: '2026-09-10 09:00:00',
      startUser: { nickname: '张三' },
      formVariables: { privateHistory: '不得回写' }
    },
    processDefinition: { modelType: 20, formType: 0 },
    todoTask:
      status === 1
        ? {
            id: 'review-task',
            name: '最终审批',
            status: 1,
            taskDefinitionKey: 'review',
            formBinding: { mode: 'OVERRIDE', source: { kind: 'NONE' } }
          }
        : undefined,
    activityNodes: [
      ...reviewItems
        .filter(item => item.state === 'CURRENT')
        .map(item => ({
          id: item.nodeId,
          name: item.nodeName,
          status: 2,
          endTime: item.submittedAt,
          tasks: [{ id: item.taskId, assigneeUser: { nickname: item.submitterName }, reason: '已提交' }]
        })),
      { id: 'review', name: '最终审批', status, tasks: [{ id: 'review-task', assigneeUser: { nickname: '管理员' } }] }
    ]
  }
}
