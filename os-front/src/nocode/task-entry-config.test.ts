// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskNodeFields from '@/views/nocode/task-center/TaskNodeFields.vue'
import { newTaskNode } from './task-center'
import type { TaskNodeInput } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
const capture = vi.hoisted(() => ({ nodes: [] as TaskNodeInput[], entries: [] as TaskWorkEntryConfig[] }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: {} }) }))
vi.mock('@/views/nocode/task-center/TaskEntriesEditor.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'nodes'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () => {
        capture.nodes = props.nodes
        capture.entries = props.modelValue
        return h(
          'button',
          {
            onClick: () =>
              emit(
                'update:modelValue',
                props.modelValue.map((entry: TaskWorkEntryConfig) => ({
                  ...entry,
                  workRule: { mode: 'RECORD_ONCE', minutes: 15 }
                }))
              )
          },
          '保存业务项'
        )
      }
  })
}))
let app: App, host: HTMLElement
async function mount(node: TaskNodeInput, nodes: TaskNodeInput[]) {
  const value = ref(node)
  app = createApp(() =>
    h(TaskNodeFields, { modelValue: value.value, nodes, members: [], section: 'business', isRoot: true })
  )
  const plain = defineComponent({
    props: ['label', 'message'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.label, props.message, slots.default?.()])
  })
  for (const name of ['AFormItem', 'AAlert', 'ACollapse', 'ACollapsePanel', 'ARadioGroup', 'ARadio'])
    app.component(name, plain)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await nextTick()
  return value
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('统一业务关联配置', () => {
  it('使用正在编辑的依赖构造业务共享来源，不读取陈旧节点副本', async () => {
    const saved = { ...newTaskNode(), id: 'editing' },
      ancestor = { ...newTaskNode(), id: 'ancestor' },
      previous = { ...newTaskNode(), id: 'previous' }
    const value = await mount({ ...saved, predecessorIds: ['previous'] }, [saved, ancestor, previous])
    expect(capture.nodes.find(node => node.id === 'editing')?.predecessorIds).toEqual(['previous'])
    value.value.predecessorIds = ['ancestor']
    await nextTick()
    expect(capture.nodes.find(node => node.id === 'editing')?.predecessorIds).toEqual(['ancestor'])
  })
  it('旧业务绑定保留同一张特殊卡片，保存工时不迁移数据或更改原授权', async () => {
    const binding = { applicationId: 'app', entryId: 'legacy', formId: 'form' }
    const value = await mount(
      { ...newTaskNode(), binding, dataPolicy: { version: 1, business: 'ALL', feedback: 'GROUP' } },
      []
    )
    expect(capture.entries).toHaveLength(1)
    expect(capture.entries[0]?.key).toBe('__business')
    expect(value.value.entries).toBeUndefined()
    host.querySelector('button')!.click()
    await nextTick()
    expect(value.value.binding).toEqual(binding)
    expect(value.value.dataPolicy).toEqual({ version: 1, business: 'ALL', feedback: 'GROUP' })
    expect(value.value.entries?.[0]).toMatchObject({
      key: '__business',
      binding,
      workRule: { mode: 'RECORD_ONCE', minutes: 15 }
    })
    expect(capture.entries).toHaveLength(1)
  })
})
