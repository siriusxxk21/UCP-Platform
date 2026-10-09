/// <reference path="../../../../../../types/bpmn-auto-layout.d.ts" />

const BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const BPMN_DI = 'http://www.omg.org/spec/BPMN/20100524/DI'

/** 已部署 XML 只用于显示；缺少 DI 时生成临时布局副本，绝不回写模型。 */
export async function prepareBpmnView(xml: string): Promise<{ xml: string; generated: boolean }> {
  if (!xml.trim()) throw new Error('暂无流程图数据')
  if (/<!DOCTYPE/i.test(xml)) throw new Error('流程图格式不支持外部文档声明')
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  if (document.querySelector('parsererror')) throw new Error('流程图数据格式有误，请联系流程管理员检查模型')
  if (document.getElementsByTagNameNS(BPMN_DI, 'BPMNShape').length) return { xml, generated: false }
  // 自动布局库仅保证首个参与者、折叠子流程；不把不完整协作图当作完整流程展示。
  if (
    document.getElementsByTagNameNS(BPMN, 'process').length !== 1 ||
    document.getElementsByTagNameNS(BPMN, 'participant').length > 1
  )
    throw new Error('此历史模型缺少图形布局，且包含多个流程或参与者，请联系流程管理员补齐布局后查看')
  if (xml.length > 1_000_000 || document.getElementsByTagNameNS(BPMN, '*').length > 500)
    throw new Error('此历史模型较大且缺少图形布局，请联系流程管理员补齐布局后查看')
  const { layoutProcess } = await import('bpmn-auto-layout')
  // Flowable 允许仅用 sequenceFlow 的 sourceRef/targetRef 定义连线，布局库还需要节点入/出引用。
  // 只在临时 DOM 补齐缺失的反向引用，保持已部署 XML 与其业务扩展不变。
  const elements = new Map(
    Array.from(document.getElementsByTagNameNS(BPMN, '*')).map(node => [node.getAttribute('id'), node])
  )
  for (const flow of Array.from(document.getElementsByTagNameNS(BPMN, 'sequenceFlow'))) {
    for (const [attribute, relation] of [
      ['sourceRef', 'outgoing'],
      ['targetRef', 'incoming']
    ]) {
      const node = elements.get(flow.getAttribute(attribute!))
      const flowId = flow.getAttribute('id')
      if (
        !node ||
        !flowId ||
        Array.from(node.children).some(child => child.localName === relation && child.textContent === flowId)
      )
        continue
      const reference = document.createElementNS(BPMN, relation!)
      reference.textContent = flowId
      node.append(reference)
    }
  }
  return { xml: await layoutProcess(new XMLSerializer().serializeToString(document)), generated: true }
}

export type FlowNodeState = 'running' | 'finished' | 'rejected'
/** 同一节点可能有多次办理，正在执行的任务优先于旧任务历史。 */
export function flowNodeStates(view?: Record<string, any>): Record<string, FlowNodeState> {
  const result: Record<string, FlowNodeState> = {}
  for (const id of view?.finishedTaskActivityIds || []) result[id] = 'finished'
  for (const task of view?.tasks || [])
    if (task.endTime) result[task.taskDefinitionKey] = task.status === 3 ? 'rejected' : 'finished'
  for (const id of view?.rejectedTaskActivityIds || []) result[id] = 'rejected'
  for (const task of view?.tasks || [])
    if (task.status === 1 && !task.endTime) result[task.taskDefinitionKey] = 'running'
  for (const id of view?.unfinishedTaskActivityIds || []) result[id] = 'running'
  return result
}
