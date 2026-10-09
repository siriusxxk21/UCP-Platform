// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import AutomationConfigEditor from '@/views/nocode/application/components/AutomationConfigEditor.vue'
import type { PublishedObject } from '@/types/nocode/application'
import type { AutomationConfig } from '@/types/nocode/automation'
import { defaultAutomation, newAutomationAssignment } from './automation'

vi.mock('@/views/nocode/application/components/SelectionField.vue', async () => {
  const { defineComponent, h, inject } = await import('vue')
  const { selectionPreviewKey } = await import('./selection')
  return {
    default: defineComponent({
      props: {
        objectId: String,
        fieldId: String,
        applicationId: String,
        modelValue: null,
        disabled: Boolean,
        preview: Boolean
      },
      emits: ['update:modelValue'],
      setup(props, { emit }) {
        const preview = inject(selectionPreviewKey)
        return () =>
          h(
            'button',
            {
              'data-picker': `${props.objectId}:${props.fieldId}`,
              'data-preview': props.preview,
              'data-objects': preview?.value.objects.map(o => `${o.objectId}:${o.versionNo}`).join(','),
              disabled: props.disabled,
              onClick: () => emit('update:modelValue', '9007199254740993')
            },
            String(props.modelValue ?? '选择记录')
          )
      }
    })
  }
})
const objects = {
  source: {
    objectId: 'source',
    versionNo: 4,
    checksum: 's4',
    definition: {
      objectId: 'source',
      objectName: '来源',
      fields: [{ id: 'ref', name: '关联目标', type: 'INTEGER' }],
      fieldOptions: {},
      relations: [{ id: 'link', name: '目标', fieldId: 'ref', kind: 'REFERENCE', targetObjectId: 'target' }],
      details: []
    }
  },
  target: {
    objectId: 'target',
    versionNo: 2,
    checksum: 't2',
    definition: {
      objectId: 'target',
      objectName: '目标',
      fields: [{ id: 'state', name: '状态', type: 'INTEGER' }],
      fieldOptions: { state: { generated: true } },
      relations: [{ id: 'statusLink', name: '状态', fieldId: 'state', kind: 'REFERENCE', targetObjectId: 'status' }],
      details: []
    }
  },
  status: {
    objectId: 'status',
    versionNo: 1,
    checksum: 's1',
    definition: { objectId: 'status', objectName: '状态', fields: [], fieldOptions: {}, relations: [], details: [] }
  }
} as unknown as Record<string, PublishedObject>
let app: App, host: HTMLDivElement
const flush = async () => {
  await nextTick()
  await nextTick()
}
async function mount(readOnly = false) {
  const state = reactive<{ config: AutomationConfig }>({
    config: {
      ...defaultAutomation('source'),
      targetObjectId: 'target',
      binding: { relationId: 'link', direction: 'OUTGOING' },
      conditions: {
        logic: 'AND',
        conditions: [],
        groups: [{ logic: 'AND', groups: [], conditions: [{ fieldId: 'ref', operator: 'eq', value: '1' }] }]
      },
      assignments: [{ ...newAutomationAssignment('MAINTAIN'), fieldId: 'state', value: '2', emptyValue: '1' }]
    }
  })
  app = createApp(() =>
    h(AutomationConfigEditor, {
      modelValue: state.config,
      objects,
      applicationId: 'app',
      readOnly,
      'onUpdate:modelValue': (v: AutomationConfig) => {
        state.config = v
      }
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ARow', 'ACol', 'ADivider', 'ASpace', 'AInput', 'AInputNumber', 'ADatePicker', 'ATimePicker'])
    app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({ props: ['message', 'description'], setup: p => () => h('p', `${p.message} ${p.description}`) })
  )
  app.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', { 'data-label': p.label }, slots.default?.())
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  const select = defineComponent({
    props: ['value', 'options', 'disabled'],
    emits: ['update:value', 'change'],
    setup:
      (p, { emit }) =>
      () =>
        h(
          'select',
          {
            value: p.value,
            disabled: p.disabled,
            onChange: (e: Event) => {
              const value = (e.target as HTMLSelectElement).value
              emit('update:value', value)
              emit('change', value)
            }
          },
          (p.options || []).map((o: { value: string; label: string }) => h('option', { value: o.value }, o.label))
        )
  })
  app.component('ASelect', select)
  app.component('ARadioGroup', select)
  app.component('ACheckboxGroup', plain)
  app.component('ASwitch', plain)
  app.component('ACheckbox', plain)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return state
}
async function select(label: string, value: string) {
  const control = host.querySelector<HTMLSelectElement>(`[data-label="${label}"] select`)!
  control.value = value
  control.dispatchEvent(new Event('change'))
  await flush()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('自动更新配置的真实编辑会话', () => {
  it('存在和空集固定值都使用目标字段的真实记录选择器，并传递草稿版本', async () => {
    const state = await mount()
    const pickers = host.querySelectorAll<HTMLButtonElement>('[data-picker="target:state"]')
    expect(pickers).toHaveLength(2)
    expect(pickers[0]?.dataset.preview).toBe('true')
    expect(pickers[0]?.dataset.objects).toBe('source:4,target:2,status:1')
    pickers[1]!.click()
    await flush()
    expect(state.config.assignments[0]?.emptyValue).toBe('9007199254740993')
    expect(state.config.assignments[0]?.value).toBe('2')
  })
  it('嵌套条件组也使用来源字段的选择器，更新同一个嵌套条件', async () => {
    const state = await mount()
    host.querySelector<HTMLButtonElement>('[data-picker="source:ref"]')!.click()
    await flush()
    expect(state.config.conditions?.groups[0]?.conditions[0]?.value).toBe('9007199254740993')
  })
  it('更换来源对象清理关系、条件和赋值，不带入旧对象稳定ID', async () => {
    const state = await mount()
    await select('来源数据对象', 'target')
    expect(state.config).toMatchObject({
      objectId: 'target',
      targetObjectId: '',
      conditions: null,
      assignments: [],
      binding: { relationId: '' }
    })
    expect(host.querySelector('[data-picker]')).toBeNull()
  })
  it('切换一次性赋值清除旧维护结果，展示可选事件', async () => {
    const state = await mount()
    await select('更新方式', 'EVENT')
    expect(state.config.events).toEqual(['CREATE', 'UPDATE'])
    expect(state.config.assignments[0]).toMatchObject({
      fieldId: 'state',
      kind: 'VALUE',
      value: null,
      emptyValue: null
    })
    expect(host.querySelector('[data-label="触发事件"]')).not.toBeNull()
  })
  it('只读查看不能通过固定值选择器和字段按钮改动配置', async () => {
    const state = await mount(true)
    const before = JSON.stringify(state.config)
    host.querySelector<HTMLButtonElement>('[data-picker="target:state"]')!.click()
    for (const button of Array.from(host.querySelectorAll<HTMLButtonElement>('button')))
      expect(button.disabled).toBe(true)
    expect(JSON.stringify(state.config)).toBe(before)
  })
})
