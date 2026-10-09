/** 这里只决定页面入口；任务资格、固定资源和完成凭据由服务端再次校验。 */
export const BUSINESS_TASK_NAMESPACE = 'https://richuang.com/schema/bpmn/business-task'
const FLOWABLE_NAMESPACE = 'http://flowable.org/bpmn'
const BPMN_NAMESPACE = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const XMLNS_NAMESPACE = 'http://www.w3.org/2000/xmlns/'
export interface BusinessNodeConfiguration {
  resource: import('@/types/nocode/work').PublishedResourceRef
  objectId: string
  operation: 'CREATE'
}
export function parseProcess(xml: string): Document {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) throw new Error('流程 XML 无效，请先修正流程设计')
  return doc
}

/** 复制仅改流程身份和对应的图形引用，不能全文替换业务配置或表达式中的字符串。 */
export function copyProcessIdentity(xml: string, key: string, name: string): string {
  const doc = parseProcess(xml)
  const processes = Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'process'))
  if (processes.length !== 1) throw new Error('当前模型包含多个流程或缺少流程，不能直接复制')
  const process = processes[0]!
  const oldKey = process.getAttribute('id')
  process.setAttribute('id', key)
  process.setAttribute('name', name)
  for (const node of Array.from(doc.getElementsByTagNameNS('http://www.omg.org/spec/BPMN/20100524/DI', 'BPMNPlane'))) {
    if (node.getAttribute('bpmnElement') === oldKey) node.setAttribute('bpmnElement', key)
  }
  for (const node of Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'participant'))) {
    if (node.getAttribute('processRef') === oldKey) node.setAttribute('processRef', key)
  }
  return new XMLSerializer().serializeToString(doc)
}

export interface BusinessTaskBindingView {
  id: string
  name: string
  handler: string
  configuration?: BusinessNodeConfiguration
  issues: string[]
}

/** 发布前的界面提示；最终资格、人员和资源权限仍由现有后端 Guard 校验。 */
export function inspectBusinessTasks(xml: string, autoApprovalType = 0): BusinessTaskBindingView[] {
  if (!xml?.trim()) return []
  const doc = parseProcess(xml)
  return Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'userTask')).flatMap(node => {
    const handler = node.getAttributeNS(BUSINESS_TASK_NAMESPACE, 'handler')
    const raw = node.getAttributeNS(BUSINESS_TASK_NAMESPACE, 'configuration')
    if (handler === null && raw === null) return []
    const issues: string[] = []
    let configuration: BusinessNodeConfiguration | undefined
    if (handler !== 'nocode') issues.push('业务办理方式尚未支持，请保留原配置并检查来源')
    try {
      const data = JSON.parse(raw || '')
      const resource = data?.resource
      if (
        data?.operation !== 'CREATE' ||
        typeof data?.objectId !== 'string' ||
        !data.objectId ||
        resource?.resourceKind !== 'FORM' ||
        typeof resource?.applicationId !== 'string' ||
        !resource.applicationId ||
        !Number.isInteger(resource?.applicationVersion) ||
        resource.applicationVersion < 1 ||
        typeof resource?.applicationChecksum !== 'string' ||
        !resource.applicationChecksum ||
        typeof resource?.resourceId !== 'string' ||
        !resource.resourceId
      )
        throw new Error()
      configuration = data
    } catch {
      issues.push('业务配置不完整或办理动作尚未支持，不能发布')
    }
    const setting = (name: string) => node.getElementsByTagNameNS(FLOWABLE_NAMESPACE, name)[0]?.textContent?.trim()
    if (autoApprovalType !== 0) issues.push('业务办理需要提交材料，请在更多设置中关闭自动去重')
    if (setting('approveType') !== '1') issues.push('业务办理节点必须由人工办理')
    if (setting('assignStartUserHandlerType') !== '1') issues.push('办理人与发起人相同时必须仍由本人办理')
    if (node.getElementsByTagNameNS(BPMN_NAMESPACE, 'multiInstanceLoopCharacteristics').length)
      issues.push('当前业务办理暂不支持会签或多实例')
    if (setting('assignEmptyHandlerType') != null) issues.push('当前业务办理不能配置无人处理策略')
    if (node.getAttribute('id') === 'StartUserNode') issues.push('请使用独立人工节点办理业务')
    return [
      {
        id: node.getAttribute('id') || '',
        name: node.getAttribute('name') || '业务任务',
        handler: handler || '',
        configuration,
        issues
      }
    ]
  })
}

/** 只替换选定节点的业务绑定，其余流程、分配规则及扩展原样保留。 */
export function bindBusinessTask(xml: string, nodeId: string, config: BusinessNodeConfiguration): string {
  const doc = parseProcess(xml)
  const node = Array.from(doc.getElementsByTagNameNS(BPMN_NAMESPACE, 'userTask')).find(
    n => n.getAttribute('id') === nodeId
  )
  if (!node) throw new Error('当前流程中找不到该人工节点')
  // 显式声明局部前缀，避免序列化器重复生成 ns1；也不能覆盖存量同名前缀。
  let prefix = 'business'
  let suffix = 0
  while (node.lookupNamespaceURI(prefix) && node.lookupNamespaceURI(prefix) !== BUSINESS_TASK_NAMESPACE)
    prefix = `business${++suffix}`
  node.setAttributeNS(XMLNS_NAMESPACE, `xmlns:${prefix}`, BUSINESS_TASK_NAMESPACE)
  node.setAttributeNS(BUSINESS_TASK_NAMESPACE, `${prefix}:handler`, 'nocode')
  node.setAttributeNS(BUSINESS_TASK_NAMESPACE, `${prefix}:configuration`, JSON.stringify(config))
  return new XMLSerializer().serializeToString(doc)
}

export function businessTaskTemplate(key: string, name: string): string {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) throw new Error('请先填写合法的流程标识（英文字母、数字、下划线）')
  const doc = document.implementation.createDocument(BPMN_NAMESPACE, 'definitions', null)
  const root = doc.documentElement
  root.setAttribute('targetNamespace', 'https://richuang.com/bpmn')
  root.setAttributeNS(XMLNS_NAMESPACE, 'xmlns:flowable', FLOWABLE_NAMESPACE)
  const add = (parent: Element, tag: string, attrs: Record<string, string>) => {
    const element = doc.createElementNS(BPMN_NAMESPACE, tag)
    Object.entries(attrs).forEach(([k, v]) => element.setAttribute(k, v))
    parent.appendChild(element)
    return element
  }
  const process = add(root, 'process', { id: key, name, isExecutable: 'true' })
  add(process, 'startEvent', { id: 'start', name: '开始' })
  add(process, 'sequenceFlow', { id: 'to_work', sourceRef: 'start', targetRef: 'business_work' })
  const work = add(process, 'userTask', { id: 'business_work', name: '填写业务资料' })
  work.setAttributeNS(FLOWABLE_NAMESPACE, 'flowable:candidateStrategy', '36')
  const extensions = add(work, 'extensionElements', {})
  for (const [tag, value] of [
    ['approveType', '1'],
    ['assignStartUserHandlerType', '1']
  ]) {
    const rule = doc.createElementNS(FLOWABLE_NAMESPACE, `flowable:${tag}`)
    rule.textContent = value!
    extensions.appendChild(rule)
  }
  add(process, 'sequenceFlow', { id: 'to_end', sourceRef: 'business_work', targetRef: 'end' })
  add(process, 'endEvent', { id: 'end', name: '完成' })
  return new XMLSerializer().serializeToString(doc)
}
export function businessTaskNodes(xml: string): Map<string, string> {
  if (!xml) return new Map()
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) return new Map()
  return new Map(
    Array.from(doc.getElementsByTagNameNS('*', 'userTask'))
      .filter(node => node.getAttributeNS(BUSINESS_TASK_NAMESPACE, 'handler') === 'nocode')
      .map(node => [node.getAttribute('id') || '', node.getAttribute('name') || '业务任务'])
  )
}
