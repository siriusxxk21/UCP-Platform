import { defaultFormNodes } from '@/nocode/form-presentation'
import type { WorkDraftContext } from '@/types/nocode/work'
import type { ObjectField } from '@/types/nocode/object'

export const legacyXml = `<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" targetNamespace="https://richuang.com/bpmn"><process id="company_work" name="公司登记流程" isExecutable="true"><startEvent id="start" name="发起"/><sequenceFlow id="to_work" sourceRef="start" targetRef="business_work"/><userTask id="business_work" name="填写业务资料"/><sequenceFlow id="to_check" sourceRef="business_work" targetRef="check"/><userTask id="check" name="负责人审批"/><sequenceFlow id="to_end" sourceRef="check" targetRef="end"/><endEvent id="end" name="完成"/></process><bpmndi:BPMNDiagram id="Diagram"><bpmndi:BPMNPlane id="Plane" bpmnElement="company_work" /></bpmndi:BPMNDiagram></definitions>`
export const simpleNode = {
  id: 'start',
  type: 10,
  name: '发起人',
  showText: '无需填写发起表单',
  childNode: {
    id: 'business_work',
    type: 13,
    name: '填写业务资料',
    showText: '公司登记表',
    childNode: {
      id: 'check',
      type: 11,
      name: '负责人审批',
      showText: '指定成员：负责人',
      childNode: { id: 'end', type: 1, name: '完成' }
    }
  }
}
export const fields: ObjectField[] = [
  '公司名称',
  '统一社会信用代码',
  '联系人',
  '联系电话',
  '登记地址',
  '邮政编码',
  '经营范围',
  '登记备注',
  '对接人员',
  '联系邮箱',
  '银行名称',
  '银行账号',
  '补充说明'
].map((name, index) => ({
  id: `f${index}`,
  code: `c_f${index}`,
  name,
  type: index === 6 || index === 12 ? 'TEXTAREA' : 'TEXT',
  required: index === 0
}))
const values = Object.fromEntries(
  fields.map((field, index) => [
    field.id!,
    index === 0
      ? '上海日创科技有限公司'
      : index === 6
        ? '软件开发、技术咨询、信息系统集成服务。'
        : index === 12
          ? '这是一份长表单模拟数据，用于验证左侧表单独立滚动和底部提交操作可见。'
          : '示例填写内容'
  ])
)
export function workContext(submitted = false): WorkDraftContext {
  const resource = {
    applicationId: 'app-preview',
    applicationVersion: 9,
    applicationChecksum: 'preview-checksum',
    resourceId: 'form-preview',
    resourceKind: 'FORM' as const
  }
  const draft = {
    id: submitted ? 'draft-submitted' : 'draft-edit',
    revision: 3,
    state: submitted ? ('SUBMITTED' as const) : ('DRAFT' as const),
    resource,
    objectId: 'company',
    recordId: null,
    baseRecordRevision: null,
    values: { ...values },
    updatedAt: Date.now()
  }
  return {
    draft,
    formName: '公司登记业务表单',
    form: { objectId: 'company', detailIds: [], nodes: defaultFormNodes(fields), options: { layout: 'horizontal' } },
    model: {
      object: { objectId: 'company', objectName: '公司', fields, fieldOptions: {}, details: [], relations: [] },
      permissions: {
        actions: ['READ', 'CREATE', 'UPDATE'],
        readFields: fields.map(f => f.id!),
        writeFields: fields.map(f => f.id!),
        readDetails: [],
        writeDetails: []
      },
      writable: true,
      generatedKey: true,
      keyFieldId: null,
      keyType: 'bigint',
      details: {}
    },
    submission: submitted
      ? {
          id: 'material-preview-1',
          draftId: draft.id,
          resource,
          objectId: 'company',
          recordId: 'company-preview-1',
          recordRevision: 'v1',
          values: { ...values },
          submittedAt: Date.now()
        }
      : null,
    currentRecord: null,
    writable: !submitted,
    blockedReason: null,
    recordChanged: false
  } as WorkDraftContext
}
export function approvalDetail(taskId = 'editing') {
  const submitted = taskId === 'submitted'
  return {
    processInstance: {
      id: 'instance-preview',
      name: '公司登记流程',
      status: 1,
      createTime: '2026-09-10 12:58:37',
      startUser: { nickname: '管理员' }
    },
    processDefinition: { modelType: 10, formType: 0 },
    todoTask: submitted
      ? undefined
      : { id: taskId, taskDefinitionKey: 'business_work', name: '填写业务资料', status: 1 },
    activityNodes: [
      { id: 'start', name: '发起人', status: 2 },
      { id: 'business_work', name: '填写业务资料', status: submitted ? 2 : 1 },
      { id: 'check', name: '负责人审批', status: submitted ? 1 : -1 },
      { id: 'end', name: '完成', status: -1 }
    ]
  }
}
