import { describe, expect, it } from 'vitest'
import {
  flowNodeStates,
  prepareBpmnView
} from '@/views/bpm/components/bpmn-process-designer/package/designer/bpmn-view'
import { legacyXml } from './fixtures/fixtures'

describe('已部署流程只读视图', () => {
  it('旧模型无 DI 时生成临时图形，保留原节点与连线', async () => {
    const result = await prepareBpmnView(legacyXml)
    expect(result.generated).toBe(true)
    const document = new DOMParser().parseFromString(result.xml, 'application/xml')
    expect(document.getElementsByTagNameNS('*', 'BPMNShape').length).toBe(4)
    expect(document.getElementsByTagNameNS('*', 'BPMNEdge').length).toBe(3)
    expect(document.getElementById('business_work')?.getAttribute('name')).toBe('填写业务资料')
    expect(legacyXml).not.toContain('BPMNShape')
  })
  it('已有 DI 时逐字保留已部署图形布局，不重新排列', async () => {
    const generated = await prepareBpmnView(legacyXml)
    expect(await prepareBpmnView(generated.xml)).toEqual({ xml: generated.xml, generated: false })
  })
  it('缺布局的多流程模型明确阻止不完整展示', async () => {
    const xml = legacyXml.replace('</definitions>', '<process id="second" /></definitions>')
    await expect(prepareBpmnView(xml)).rejects.toThrow('多个流程或参与者')
  })
  it.each(['', '<definitions><invalid>', '<!DOCTYPE definitions SYSTEM "file:///private"><definitions/>'])(
    '格式错误或外部声明安全显示错误：%s',
    async xml => {
      await expect(prepareBpmnView(xml)).rejects.toThrow()
    }
  )
  it('循环重办时当前运行标记优先于同节点旧已办/拒绝记录', () => {
    expect(
      flowNodeStates({
        finishedTaskActivityIds: ['work', 'old'],
        rejectedTaskActivityIds: ['work', 'reject'],
        tasks: [
          { taskDefinitionKey: 'work', status: 1 },
          { taskDefinitionKey: 'work', status: 3, endTime: '2026-09-09' }
        ]
      })
    ).toEqual({ work: 'running', old: 'finished', reject: 'rejected' })
  })
})
