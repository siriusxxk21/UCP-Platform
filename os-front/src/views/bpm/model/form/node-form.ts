import {
  bindBusinessTask,
  BUSINESS_TASK_NAMESPACE,
  inspectBusinessTasks,
  parseProcess,
  type BusinessNodeConfiguration
} from '@/nocode/flow-task-binding'

export const NODE_FORM_NAMESPACE = 'https://richuang.com/schema/bpmn/node-form'
const BPMN_NAMESPACE = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const FLOWABLE_NAMESPACE = 'http://flowable.org/bpmn'
export type FormSource =
  | { kind: 'NONE' }
  | { kind: 'FLOW_FORM'; formId?: string }
  | { kind: 'SYSTEM_ROUTE'; createPath?: string; viewPath?: string }
  | { kind: 'APPLICATION_RESOURCE'; configuration?: BusinessNodeConfiguration }
export interface NodeFormBinding {
  mode: 'INHERIT' | 'OVERRIDE'
  source?: FormSource
  taskMode?: 'APPROVAL' | 'BUSINESS'
  materialReview?: MaterialReview
  [key: string]: unknown
}
/** 查阅权限与本节点填写来源独立；缺省配置不扩大旧流程的业务读取权限。 */
export interface MaterialReview {
  scope: 'PREVIOUS' | 'NONE'
  access: 'TASK' | 'BUSINESS'
}
export function materialReviewSummary(review?: MaterialReview) {
  if (review && materialReviewError(review)) return '材料查阅规则尚未支持'
  if (review?.scope === 'NONE') return '不查阅前序材料'
  return review?.access === 'TASK' ? '前序提交材料 · 任务范围只读' : '前序提交材料 · 按业务读取权限'
}
function materialReviewError(review?: MaterialReview) {
  return review && (!['PREVIOUS', 'NONE'].includes(review.scope) || !['TASK', 'BUSINESS'].includes(review.access))
    ? '材料查阅配置尚未支持，请保留原配置'
    : undefined
}
export interface NodeFormView {
  id: string
  name: string
  binding?: NodeFormBinding
  error?: string
}

/** 复用当前业务任务预检，不把新表单协议当作绕过人工提交规则的入口。 */
export function inspectConfiguredBusinessTasks(data: any, type: number, autoApprovalType = 0) {
  if (type === 10) {
    let preview = data
    for (const node of bpmnNodeForms(data)) {
      const source = node.binding?.source
      if (source?.kind === 'APPLICATION_RESOURCE' && source.configuration)
        preview = bindBusinessTask(preview, node.id, source.configuration)
    }
    return inspectBusinessTasks(preview, autoApprovalType)
  }
  const result: import('@/nocode/flow-task-binding').BusinessTaskBindingView[] = []
  const visit = (node: any) => {
    if (!node) return
    const source = node.formBinding?.source
    if (source?.kind === 'APPLICATION_RESOURCE') {
      const issues: string[] = []
      if (autoApprovalType !== 0) issues.push('业务办理需要提交材料，请关闭自动去重')
      if (
        node.approveType !== 1 ||
        node.approveMethod !== 1 ||
        node.assignStartUserHandlerType !== 1 ||
        node.assignEmptyHandler != null
      )
        issues.push('请在节点表单配置中调整为人工单人办理，保留人员选择')
      result.push({ id: node.id, name: node.name, handler: 'nocode', configuration: source.configuration, issues })
    }
    node.conditionNodes?.forEach(visit)
    visit(node.childNode)
  }
  visit(data)
  return result
}
export function startingForm(model: Record<string, any>): FormSource {
  if (model.formType === 0) return { kind: 'NONE' }
  return model.formType === 20
    ? { kind: 'SYSTEM_ROUTE', createPath: model.formCustomCreatePath, viewPath: model.formCustomViewPath }
    : { kind: 'FLOW_FORM', formId: model.formId == null ? undefined : String(model.formId) }
}
export function effectiveForm(binding: NodeFormBinding | undefined, inherited: FormSource) {
  return !binding || binding.mode === 'INHERIT' ? inherited : binding.source
}
export function formSourceSummary(source?: FormSource, forms: Array<{ id?: string | number; name: string }> = []) {
  if (!source) return '尚未选择表单'
  if (source.kind === 'NONE') return '无需填写表单'
  if (source.kind === 'FLOW_FORM')
    return source.formId
      ? `流程表单 · ${forms.find(f => String(f.id) === source.formId)?.name || source.formId}`
      : '尚未选择发起表单'
  if (source.kind === 'SYSTEM_ROUTE') return `系统业务路由 · ${source.createPath || '未设置提交路径'}`
  const resource = source.configuration?.resource
  return resource
    ? `应用 ${resource.applicationId} · V${resource.applicationVersion} · ${resource.resourceId}`
    : '尚未选择应用表单'
}
export function bindingError(binding: NodeFormBinding): string | undefined {
  if (!binding || !['INHERIT', 'OVERRIDE'].includes(binding.mode)) return '表单配置模式尚未支持，请保留原配置'
  const reviewError = materialReviewError(binding.materialReview)
  if (reviewError) return reviewError
  if (binding.taskMode && !['APPROVAL', 'BUSINESS'].includes(binding.taskMode)) return '节点办理方式尚未支持'
  if (
    binding.taskMode === 'BUSINESS' &&
    (binding.mode !== 'OVERRIDE' || binding.source?.kind !== 'APPLICATION_RESOURCE')
  )
    return '业务任务必须独立绑定可提交的应用表单'
  if (binding.taskMode === 'APPROVAL' && binding.source?.kind === 'APPLICATION_RESOURCE')
    return '应用业务表单需使用业务任务方式提交'
  if (binding.mode === 'INHERIT') return binding.source ? '继承表单不能同时设置独立来源' : undefined
  const source = binding.source
  if (!source || !['NONE', 'FLOW_FORM', 'SYSTEM_ROUTE', 'APPLICATION_RESOURCE'].includes(source.kind))
    return '表单来源缺失或尚未支持'
  if (source.kind === 'FLOW_FORM' && !/^\d+$/.test(source.formId || '')) return '请选择流程表单'
  if (source.kind === 'SYSTEM_ROUTE') return '系统业务路由尚未注册节点办理接口'
  if (source.kind === 'APPLICATION_RESOURCE' && !source.configuration?.resource?.resourceId)
    return '请选择应用已发布的业务表单'
}
export function bpmnNodeForms(xml: string): NodeFormView[] {
  if (!xml?.trim()) return []
  const doc = parseProcess(xml)
  const legacy = inspectBusinessTasks(xml)
  return Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'userTask'))
    .filter(node => node.getAttribute('id') !== 'StartUserNode')
    .map(node => {
      const id = node.getAttribute('id') || ''
      const result: NodeFormView = { id, name: node.getAttribute('name') || id }
      const raw = node.getAttributeNS(NODE_FORM_NAMESPACE, 'configuration')
      if (raw !== null) {
        try {
          result.binding = JSON.parse(raw)
          const binding = result.binding
          if (
            !binding ||
            !['INHERIT', 'OVERRIDE'].includes(binding.mode) ||
            (binding.source &&
              !['NONE', 'FLOW_FORM', 'SYSTEM_ROUTE', 'APPLICATION_RESOURCE'].includes(binding.source.kind))
          )
            result.error = '节点表单配置尚未支持，请保留原配置'
          else result.error = materialReviewError(binding.materialReview)
        } catch {
          result.error = '节点表单配置损坏，请先修正源码'
        }
      } else {
        const old = legacy.find(item => item.id === id)
        if (old) {
          if (old.handler !== 'nocode' || !old.configuration) result.error = '旧业务绑定暂不支持编辑，原配置已保留'
          else
            result.binding = {
              mode: 'OVERRIDE',
              source: { kind: 'APPLICATION_RESOURCE', configuration: old.configuration }
            }
        } else {
          const key = node.getAttributeNS(FLOWABLE_NAMESPACE, 'formKey')
          if (key) {
            if (/^\d+$/.test(key)) result.binding = { mode: 'OVERRIDE', source: { kind: 'FLOW_FORM', formId: key } }
            else result.error = '旧表单标识暂不支持编辑，原配置已保留'
          }
        }
      }
      return result
    })
}
/** 用户明确应用当前节点后才替换绑定；流程身份、其他节点及未知扩展均不变。 */
export function setBpmnNodeForm(xml: string, id: string, binding: NodeFormBinding, manual = false): string {
  const view = bpmnNodeForms(xml).find(node => node.id === id)
  if (!view || view.error) throw new Error(view?.error || '找不到当前节点')
  const error = bindingError(binding)
  if (error) throw new Error(error)
  const doc = parseProcess(xml)
  const node = Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'userTask')).find(
    item => item.getAttribute('id') === id
  )!
  node.removeAttributeNS(BUSINESS_TASK_NAMESPACE, 'handler')
  node.removeAttributeNS(BUSINESS_TASK_NAMESPACE, 'configuration')
  node.removeAttributeNS(NODE_FORM_NAMESPACE, 'resolved')
  node.removeAttributeNS(FLOWABLE_NAMESPACE, 'formKey')
  let prefix = 'nodeForm',
    suffix = 0
  while (node.lookupNamespaceURI(prefix) && node.lookupNamespaceURI(prefix) !== NODE_FORM_NAMESPACE)
    prefix = `nodeForm${++suffix}`
  node.setAttributeNS('http://www.w3.org/2000/xmlns/', `xmlns:${prefix}`, NODE_FORM_NAMESPACE)
  node.setAttributeNS(NODE_FORM_NAMESPACE, `${prefix}:configuration`, JSON.stringify(binding))
  if (manual) {
    node.removeAttributeNS(FLOWABLE_NAMESPACE, 'skipExpression')
    let extensions = Array.from(node.children).find(
      child => child.localName === 'extensionElements' && child.namespaceURI === BPMN_NAMESPACE
    )
    if (!extensions) {
      extensions = doc.createElementNS(BPMN_NAMESPACE, 'extensionElements')
      node.prepend(extensions)
    }
    for (const name of ['approveType', 'assignStartUserHandlerType', 'assignEmptyHandlerType']) {
      for (const old of Array.from(extensions.getElementsByTagNameNS(FLOWABLE_NAMESPACE, name))) old.remove()
      if (name !== 'assignEmptyHandlerType') {
        const setting = doc.createElementNS(FLOWABLE_NAMESPACE, `flowable:${name}`)
        setting.textContent = '1'
        extensions.appendChild(setting)
      }
    }
    for (const loop of Array.from(node.getElementsByTagNameNS(BPMN_NAMESPACE, 'multiInstanceLoopCharacteristics')))
      loop.remove()
  }
  return new XMLSerializer().serializeToString(doc)
}
