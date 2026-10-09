import { describe, expect, it } from 'vitest'
import { bindBusinessTask, businessTaskTemplate, parseProcess } from '@/nocode/flow-task-binding'
import {
  bpmnNodeForms,
  inspectConfiguredBusinessTasks,
  effectiveForm,
  setBpmnNodeForm,
  startingForm,
  NODE_FORM_NAMESPACE
} from '@/views/bpm/model/form/node-form'
import { bindingError } from '@/views/bpm/model/form/node-form'
import { modelPayload } from '@/views/bpm/model/form/model-editor'

const original = businessTaskTemplate('form_check', '配置测试')
describe('两类设计器共同表单规则', () => {
  it('审批材料权限独立于填写来源，NONE 和继承模式均可保存且不扩充旧绑定', () => {
    const review = { scope: 'PREVIOUS' as const, access: 'TASK' as const }
    const noInput = {
      mode: 'OVERRIDE' as const,
      taskMode: 'APPROVAL' as const,
      source: { kind: 'NONE' as const },
      materialReview: review
    }
    expect(bindingError(noInput)).toBeUndefined()
    const saved = setBpmnNodeForm(original, 'business_work', noInput)
    expect(bpmnNodeForms(saved)[0]?.binding).toEqual(noInput)
    const inherit = { mode: 'INHERIT' as const, materialReview: review }
    expect(bpmnNodeForms(setBpmnNodeForm(saved, 'business_work', inherit))[0]?.binding).toEqual(inherit)
    const legacy = { mode: 'INHERIT' as const }
    expect(bpmnNodeForms(setBpmnNodeForm(original, 'business_work', legacy))[0]?.binding).toEqual(legacy)
  })
  it('未知材料范围和授权值不能发布为默认权限', () => {
    for (const materialReview of [
      { scope: 'ALL', access: 'TASK' },
      { scope: 'PREVIOUS', access: 'PUBLIC' }
    ]) {
      const binding = { mode: 'INHERIT' as const, materialReview } as any
      expect(bindingError(binding)).toContain('材料查阅配置')
      expect(() => setBpmnNodeForm(original, 'business_work', binding)).toThrow('材料查阅配置')
    }
  })
  it('明确应用人工办理才清除节点跳过规则，普通换绑仍保留原规则', () => {
    const doc = parseProcess(original)
    doc
      .getElementsByTagNameNS('*', 'userTask')[0]!
      .setAttributeNS('http://flowable.org/bpmn', 'flowable:skipExpression', '${true}')
    const xml = new XMLSerializer().serializeToString(doc)
    const binding = { mode: 'OVERRIDE' as const, source: { kind: 'FLOW_FORM' as const, formId: '2' } }
    const skip = (value: string) =>
      parseProcess(value)
        .getElementsByTagNameNS('*', 'userTask')[0]!
        .getAttributeNS('http://flowable.org/bpmn', 'skipExpression')
    expect(skip(setBpmnNodeForm(xml, 'business_work', binding))).toBe('${true}')
    expect(skip(setBpmnNodeForm(xml, 'business_work', binding, true))).toBeNull()
    expect(skip(xml)).toBe('${true}')
  })
  it('无需发起表单清除旧引用，独立节点仍保留自己的表单', () => {
    const none = startingForm({ formType: 0, formId: 9 })
    expect(none).toEqual({ kind: 'NONE' })
    expect(effectiveForm({ mode: 'INHERIT' }, none)).toEqual(none)
    const binding = { mode: 'OVERRIDE' as const, source: { kind: 'FLOW_FORM' as const, formId: '2' } }
    expect(effectiveForm(binding, none)).toEqual(binding.source)
    const payload = modelPayload(
      {
        type: 10,
        formType: 0,
        formId: '9',
        formCustomCreatePath: '/old',
        formCustomViewPath: '/old-view',
        startUserIds: [],
        startDeptIds: [],
        startUserType: 0
      },
      original,
      {},
      {}
    )
    expect(payload.formId).toBeNull()
    expect(payload.formCustomCreatePath).toBe('')
    expect(payload.formCustomViewPath).toBe('')
    expect(payload.bpmnXml).toBe(original)
  })
  it('独立无表单可以往返，业务任务和审批的完成方式不得混用', () => {
    const binding = { mode: 'OVERRIDE' as const, taskMode: 'APPROVAL' as const, source: { kind: 'NONE' as const } }
    expect(bpmnNodeForms(setBpmnNodeForm(original, 'business_work', binding))[0]?.binding).toEqual(binding)
    expect(bindingError({ mode: 'INHERIT', taskMode: 'BUSINESS' })).toContain('业务任务')
    expect(bindingError({ ...binding, taskMode: 'BUSINESS' })).toContain('业务任务')
    expect(
      bindingError({ mode: 'OVERRIDE', taskMode: 'BUSINESS', source: { kind: 'APPLICATION_RESOURCE' } })
    ).toContain('已发布')
    expect(
      bindingError({ mode: 'OVERRIDE', taskMode: 'APPROVAL', source: { kind: 'APPLICATION_RESOURCE' } })
    ).toContain('业务任务')
  })
  it('简单流程分支中的业务节点也必须通过人工办理预检', () => {
    const source = { kind: 'APPLICATION_RESOURCE', configuration: {} }
    const data = {
      childNode: {
        conditionNodes: [
          {
            childNode: {
              id: 'work',
              name: '办理',
              formBinding: { mode: 'OVERRIDE', source },
              approveType: 1,
              approveMethod: 1,
              assignStartUserHandlerType: 1
            }
          }
        ]
      }
    }
    expect(inspectConfiguredBusinessTasks(data, 20, 0)[0]?.issues).toEqual([])
    expect(inspectConfiguredBusinessTasks(data, 20, 1)[0]?.issues).toContain('业务办理需要提交材料，请关闭自动去重')
    data.childNode.conditionNodes[0]!.childNode.approveMethod = 2
    expect(inspectConfiguredBusinessTasks(data, 20, 0)[0]?.issues.length).toBe(1)
  })
  it('继承随发起配置变化，独立节点保持原来源', () => {
    const first = startingForm({ formType: 10, formId: 1 }),
      second = startingForm({ formType: 10, formId: 2 })
    expect(effectiveForm(undefined, first)).toEqual(first)
    expect(effectiveForm({ mode: 'INHERIT' }, second)).toEqual(second)
    expect(effectiveForm({ mode: 'OVERRIDE', source: first }, second)).toEqual(first)
  })
  it('独立配置和切回继承可往返保存，保留其他节点规则', () => {
    const override = setBpmnNodeForm(original, 'business_work', {
      mode: 'OVERRIDE',
      source: { kind: 'FLOW_FORM', formId: '2' },
      future: { key: 'kept' }
    })
    expect(bpmnNodeForms(override)[0]?.binding?.future).toEqual({ key: 'kept' })
    const inherited = setBpmnNodeForm(override, 'business_work', { mode: 'INHERIT' })
    expect(bpmnNodeForms(inherited)[0]?.binding).toEqual({ mode: 'INHERIT' })
    expect(
      parseProcess(inherited).getElementsByTagNameNS('http://flowable.org/bpmn', 'approveType')[0]?.textContent
    ).toBe('1')
    expect(parseProcess(inherited).getElementsByTagNameNS('*', 'process')[0]?.getAttribute('id')).toBe('form_check')
  })
  it('旧业务绑定回显为独立来源，明确应用后迁移且保持原版本', () => {
    const configuration = {
      resource: {
        applicationId: '1',
        applicationVersion: 9,
        applicationChecksum: 'hash',
        resourceKind: 'FORM' as const,
        resourceId: 'r'
      },
      objectId: '2',
      operation: 'CREATE' as const
    }
    const xml = bindBusinessTask(original, 'business_work', configuration)
    const binding = bpmnNodeForms(xml)[0]!.binding!
    expect(binding.source).toEqual({ kind: 'APPLICATION_RESOURCE', configuration })
    const next = setBpmnNodeForm(xml, 'business_work', binding)
    expect(bpmnNodeForms(next)[0]?.binding).toEqual(binding)
    expect(
      parseProcess(next)
        .getElementsByTagNameNS('*', 'userTask')[0]
        ?.getAttributeNS('urn:ucp-platform:bpmn:business-task', 'handler')
    ).toBeNull()
  })
  it('损坏绑定和未知旧办理来源不能被默认继承覆盖', () => {
    const doc = parseProcess(original),
      node = doc.getElementsByTagNameNS('*', 'userTask')[0]!
    node.setAttributeNS(NODE_FORM_NAMESPACE, 'n:configuration', 'null')
    const xml = new XMLSerializer().serializeToString(doc)
    expect(bpmnNodeForms(xml)[0]?.error).toBeTruthy()
    expect(() => setBpmnNodeForm(xml, 'business_work', { mode: 'INHERIT' })).toThrow()
  })
})
