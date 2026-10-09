import { describe, expect, it } from 'vitest'
import {
  cloneModel,
  hydrateModel,
  modelPayload,
  parseSimpleModel,
  StartScope
} from '@/views/bpm/model/form/model-editor'
import {
  bindBusinessTask,
  businessTaskTemplate,
  copyProcessIdentity,
  inspectBusinessTasks,
  parseProcess,
  BUSINESS_TASK_NAMESPACE
} from '@/nocode/flow-task-binding'

const defaults = {
  type: 10,
  processIdRule: { enable: false, length: 5 },
  titleSetting: { enable: false, title: '' },
  summarySetting: { enable: false, summary: [] }
}
const resource = {
  applicationId: 'demo',
  applicationVersion: 7,
  applicationChecksum: 'fixed-release',
  resourceId: 'form-demo',
  resourceKind: 'FORM' as const
}
const config = { resource, objectId: 'company', operation: 'CREATE' as const, futureSetting: { value: 'demo 旧流程' } }

describe('存量模型编辑往返', () => {
  it('同时配置用户和部门时，两种范围均保留', () => {
    const source = { startUserIds: [1], startDeptIds: [2], managerUserIds: [1] }
    const form = hydrateModel(defaults, source),
      initial = cloneModel(form)
    form.name = '只改名称'
    const payload = modelPayload(form, '<xml/>', source, initial)
    expect(form.startUserType).toBe(StartScope.BOTH)
    expect(payload.startUserIds).toEqual(['1'])
    expect(payload.startDeptIds).toEqual(['2'])
    expect(payload).not.toHaveProperty('startUserType')
  })
  it('null、缺省、触发器、打印设置不被控件默认值替换', () => {
    const source = {
      processIdRule: null,
      titleSetting: null,
      taskAfterTriggerSetting: { url: 'https://example.com/task', body: [{ key: 'x', value: 'y' }] },
      printTemplateSetting: { enable: true, template: '<p>旧模板</p>' }
    }
    const form = hydrateModel(defaults, source),
      initial = cloneModel(form)
    expect(form.processIdRule.length).toBe(5)
    form.description = '其他修改'
    const payload = modelPayload(form, '<xml/>', source, initial)
    expect(payload.processIdRule).toBeNull()
    expect(payload.titleSetting).toBeNull()
    expect(payload).not.toHaveProperty('summarySetting')
    expect(payload.taskAfterTriggerSetting).toEqual(source.taskAfterTriggerSetting)
    expect(payload.printTemplateSetting).toEqual(source.printTemplateSetting)
  })
  it('明确编辑编号规则后才提交新的规则', () => {
    const source = { processIdRule: null },
      form = hydrateModel(defaults, source),
      initial = cloneModel(form)
    form.processIdRule.enable = true
    expect(modelPayload(form, '<xml/>', source, initial).processIdRule).toEqual({ enable: true, length: 5 })
  })
  it('模式切换不丢编辑缓冲，提交范围以最终选择为准', () => {
    const form = hydrateModel(defaults, { startUserIds: [1], startDeptIds: [2] }),
      initial = cloneModel(form)
    form.startUserType = StartScope.USER
    expect(modelPayload(form, '', {}, initial).startDeptIds).toEqual([])
    form.startUserType = StartScope.BOTH
    expect(modelPayload(form, '', {}, initial).startDeptIds).toEqual(['2'])
  })
  it('损坏 SIMPLE 数据阻断加载；未知节点属性保留在界面载荷中', () => {
    expect(() => parseSimpleModel('{')).toThrow()
    expect(() => parseSimpleModel('[]')).toThrow()
    const tree = { id: 'start', type: 10, future: { flag: true } }
    const form = hydrateModel(defaults, { type: 20, simpleModel: JSON.stringify(tree) })
    expect(modelPayload(form, form.simpleModel, {}, form).simpleModel).toEqual(tree)
    expect(tree).toEqual({ id: 'start', type: 10, future: { flag: true } })
  })
})

describe('任务绑定与流程 XML 兼容', () => {
  it('复制只改变流程身份，任务配置、表达式、节点身份和扩展保持', () => {
    const bound = bindBusinessTask(businessTaskTemplate('demo', '旧流程'), 'business_work', config)
    const doc = parseProcess(bound)
    const note = doc.createElementNS('urn:legacy', 'legacy:setting')
    note.textContent = '${demo} 旧流程'
    doc.documentElement.appendChild(note)
    const plane = doc.createElementNS('http://www.omg.org/spec/BPMN/20100524/DI', 'bpmndi:BPMNPlane')
    plane.setAttribute('bpmnElement', 'demo')
    doc.documentElement.appendChild(plane)
    const copied = parseProcess(copyProcessIdentity(new XMLSerializer().serializeToString(doc), 'demo_copy', '新流程'))
    expect(copied.getElementsByTagNameNS('*', 'process')[0]?.getAttribute('id')).toBe('demo_copy')
    expect(copied.getElementsByTagNameNS('*', 'BPMNPlane')[0]?.getAttribute('bpmnElement')).toBe('demo_copy')
    expect(copied.getElementsByTagNameNS('urn:legacy', 'setting')[0]?.textContent).toBe('${demo} 旧流程')
    const node = copied.getElementsByTagNameNS('*', 'userTask')[0]!
    expect(node.getAttribute('id')).toBe('business_work')
    expect(JSON.parse(node.getAttributeNS(BUSINESS_TASK_NAMESPACE, 'configuration')!)).toEqual(config)
  })
  it('固定版本保持且合法人工业务节点不报冲突', () => {
    const xml = bindBusinessTask(businessTaskTemplate('demo', '测试'), 'business_work', config)
    expect(inspectBusinessTasks(xml)[0]?.configuration?.resource.applicationVersion).toBe(7)
    expect(inspectBusinessTasks(xml)[0]?.issues).toEqual([])
    expect(inspectBusinessTasks(xml, 2)[0]?.issues.join()).toContain('关闭自动去重')
  })
  it('替换一个节点绑定不改另一个节点和扩展', () => {
    const doc = parseProcess(bindBusinessTask(businessTaskTemplate('demo', '测试'), 'business_work', config))
    const work = doc.getElementsByTagNameNS('*', 'userTask')[0]!,
      other = work.cloneNode(true) as Element
    other.setAttribute('id', 'other')
    work.parentElement!.appendChild(other)
    const changed = bindBusinessTask(new XMLSerializer().serializeToString(doc), 'business_work', {
      ...config,
      resource: { ...resource, applicationVersion: 8 }
    })
    expect(inspectBusinessTasks(changed).map(b => b.configuration?.resource.applicationVersion)).toEqual([8, 7])
  })
  it('未知办理动作保留但阻止发布', () => {
    const xml = bindBusinessTask(businessTaskTemplate('demo', '测试'), 'business_work', {
      ...config,
      operation: 'UPDATE'
    } as any)
    const result = inspectBusinessTasks(xml)[0]!
    expect(result.configuration).toBeUndefined()
    expect(result.issues.join()).toContain('尚未支持')
    expect(xml).toContain('UPDATE')
  })
  it('会签和自动跳过设置提示与任务提交冲突', () => {
    const doc = parseProcess(bindBusinessTask(businessTaskTemplate('demo', '测试'), 'business_work', config))
    doc.getElementsByTagNameNS('*', 'approveType')[0]!.textContent = '2'
    doc
      .getElementsByTagNameNS('*', 'userTask')[0]!
      .appendChild(
        doc.createElementNS('http://www.omg.org/spec/BPMN/20100524/MODEL', 'multiInstanceLoopCharacteristics')
      )
    expect(inspectBusinessTasks(new XMLSerializer().serializeToString(doc))[0]?.issues.join()).toContain('人工办理')
    expect(inspectBusinessTasks(new XMLSerializer().serializeToString(doc))[0]?.issues.join()).toContain('多实例')
  })
  it('损坏 XML 和缺少指定节点时不生成替代流程', () => {
    expect(() => copyProcessIdentity('<broken>', 'x', 'y')).toThrow()
    expect(() => bindBusinessTask(businessTaskTemplate('demo', '测试'), 'absent', config)).toThrow()
    expect(() => copyProcessIdentity('<root/>', 'x', 'y')).toThrow()
  })
})
