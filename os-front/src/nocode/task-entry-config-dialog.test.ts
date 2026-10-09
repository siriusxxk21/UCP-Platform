// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onMounted, provide, inject, type App } from 'vue'
import TaskEntryConfigDialog from '@/views/nocode/task-center/TaskEntryConfigDialog.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { TaskDataPolicy } from '@/types/nocode/task-center'
const confirm = vi.hoisted(() => vi.fn())
vi.mock('ant-design-vue', () => ({ Modal: { confirm } }))
vi.mock('@/views/nocode/task-center/TaskBindingPicker.vue', () => ({
  default: defineComponent({
    emits: ['rule-fields', 'field-options'],
    setup(_, { emit }) {
      onMounted(() => {
        emit('rule-fields', [
          { id: 'quantity', name: '设备数量', type: 'INTEGER' },
          { id: 'state', name: '施工状态', type: 'SELECT' },
          { id: 'checked', name: '已检查', type: 'BOOLEAN' }
        ])
        emit('field-options', { state: { options: [{ code: 'DONE', label: '已完成' }] } })
      })
      return () => null
    }
  })
}))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open'],
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', [slots.formItems?.(), slots.footer?.()]) : null
  })
}))
let app: App, host: HTMLElement
const flush = async () => {
  await nextTick()
  await nextTick()
}
function entry(): TaskWorkEntryConfig {
  return {
    key: 'wifi',
    name: 'Wi-Fi 配置',
    binding: { applicationId: 'app', viewId: 'room', formId: 'form', entryId: null },
    dataMode: 'ROOT_SHARED',
    sourceNodeId: null,
    sourceEntryKey: null,
    readableFieldIds: null,
    writableFieldIds: null,
    required: false,
    allowAll: false,
    workRule: { mode: 'RECORD_ONCE', minutes: 15 }
  }
}
async function mount(original: TaskWorkEntryConfig, readonly = false, legacyPolicy?: TaskDataPolicy) {
  const save = vi.fn(),
    cancel = vi.fn()
  app = createApp(() =>
    h(TaskEntryConfigDialog, {
      entry: original,
      readonly,
      unified: true,
      legacyPolicy,
      sourceInfo: { applicationName: '房间管理', viewName: 'Wi-Fi 视图', formName: 'Wi-Fi 表单' },
      onSave: save,
      onCancel: cancel
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AForm', 'AFormItem', 'ASpace', 'ACollapse', 'ACollapsePanel', 'ATag']) app.component(name, plain)
  app.component('AAlert', defineComponent({ props: ['message'], setup: props => () => h('p', props.message) }))
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'change'],
      setup(props, { emit, slots }) {
        provide('scope-radio', {
          props,
          update: (value: string) => {
            emit('update:value', value)
            emit('change', { target: { value } })
          }
        })
        return () => h('div', { role: 'radiogroup' }, slots.default?.())
      }
    })
  )
  app.component(
    'ARadio',
    defineComponent({
      props: ['value'],
      setup(props, { slots }) {
        const group = inject<{ props: { value: string; disabled: boolean }; update: (value: string) => void }>(
          'scope-radio'
        )!
        return () =>
          h('label', [
            h('input', {
              type: 'radio',
              value: props.value,
              checked: group.props.value === props.value,
              disabled: group.props.disabled,
              onChange: () => group.update(props.value)
            }),
            slots.default?.()
          ])
      }
    })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            type: 'number',
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) => {
                const value = props.options?.find(
                  (option: { value: unknown }) => String(option.value) === (event.target as HTMLSelectElement).value
                )?.value
                emit('update:value', value)
                emit('change')
              }
            },
            props.options?.map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'ASwitch',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) => emit('change', (event.target as HTMLInputElement).checked)
          })
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked'],
      emits: ['update:checked'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      emits: ['click'],
      setup:
        (_, { slots, emit }) =>
        () =>
          h('button', { onClick: () => emit('click') }, slots.default?.())
    })
  )
  app.component('ADatePicker', plain)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { save, cancel }
}
async function click(label: string) {
  Array.from(host.querySelectorAll('button'))
    .find(button => button.textContent === label)!
    .click()
  await flush()
}
async function input(selector: string, value: string) {
  const target = host.querySelector<HTMLInputElement>(selector)!
  target.value = value
  target.dispatchEvent(new Event('input'))
  await flush()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  confirm.mockReset()
})
describe('业务办理项配置弹窗', () => {
  it('关联来源默认收起，可按需查看应用、视图和表单，不改变草稿', async () => {
    const original = entry(),
      before = JSON.stringify(original)
    const { cancel } = await mount(original)
    const source = host.querySelector('details')!
    expect(source.open).toBe(false)
    source.querySelector('summary')!.click()
    await flush()
    expect(source.open).toBe(true)
    expect(source.textContent).toContain('房间管理')
    expect(source.textContent).toContain('Wi-Fi 视图')
    expect(source.textContent).toContain('Wi-Fi 表单')
    await click('取消')
    expect(cancel).toHaveBeenCalledOnce()
    expect(confirm).not.toHaveBeenCalled()
    expect(JSON.stringify(original)).toBe(before)
  })
  it('范围只保存到当前项，不改写历史全局策略；取消前不污染原对象', async () => {
    const original = { ...entry(), dataScope: 'GROUP' as const }
    const policy = { version: 1 as const, business: 'GROUP' as const, feedback: 'GROUP' as const }
    const { save } = await mount(original, false, policy)
    const all = host.querySelector<HTMLInputElement>('input[type="radio"][value="ALL"]')!
    all.click()
    await flush()
    expect(original.dataScope).toBe('GROUP')
    expect(all.checked).toBe(true)
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ key: 'wifi', dataScope: 'ALL' }))
    expect(policy.feedback).toBe('GROUP')
  })
  it('历史范围按原授权显示，未调整范围保存时不自动写入默认值', async () => {
    const original = { ...entry(), key: '__business' }
    const { save, cancel } = await mount(original, false, { version: 1, business: 'ALL', feedback: 'GROUP' })
    expect(host.querySelector<HTMLInputElement>('input[value="ALL"]')?.checked).toBe(true)
    await click('取消')
    expect(cancel).toHaveBeenCalledOnce()
    expect(confirm).not.toHaveBeenCalled()
    await click('保存配置')
    expect(save.mock.calls[0]![0].dataScope).toBeUndefined()
  })
  it('只读配置的范围不能改动', async () => {
    await mount({ ...entry(), dataScope: 'GROUP' }, true)
    expect(host.querySelector<HTMLInputElement>('input[value="ALL"]')?.disabled).toBe(true)
  })
  it('新视图未配置工时直接保存，不自动打开计时或产生关闭保护', async () => {
    const original = { ...entry(), workRule: null },
      { save, cancel } = await mount(original)
    expect(host.textContent).not.toContain('必填')
    expect(host.querySelector('[aria-label="标准工时（分钟）"]')).toBeNull()
    expect(original.workRule).toBeNull()
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ workRule: null }))
    await click('取消')
    expect(cancel).toHaveBeenCalledOnce()
    expect(confirm).not.toHaveBeenCalled()
  })
  it('历史表单未设置标准工时仍可保存，不强迫改写历史规则', async () => {
    const original = entry()
    original.binding!.viewId = null
    original.workRule = null
    const { save } = await mount(original)
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ workRule: null }))
  })
  it('编辑副本不污染原配置，小时分钟保存为整数分钟且保留稳定key', async () => {
    const original = entry(),
      { save } = await mount(original)
    await input('[aria-label="标准工时（小时）"]', '2')
    await input('[aria-label="标准工时（分钟）"]', '30')
    expect(original.workRule?.minutes).toBe(15)
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'wifi', workRule: { mode: 'RECORD_ONCE', minutes: 150 } })
    )
  })
  it('修改后关闭需要确认；保留原规则与原字段权限', async () => {
    const original = entry(),
      { cancel, save } = await mount(original)
    await input('[placeholder="如：房间 Wi-Fi 配置"]', '新的名称')
    await click('取消')
    expect(confirm).toHaveBeenCalledOnce()
    expect(cancel).not.toHaveBeenCalled()
    expect(save).not.toHaveBeenCalled()
    expect(original.name).toBe('Wi-Fi 配置')
    await confirm.mock.calls[0]![0].onOk()
    expect(cancel).toHaveBeenCalledOnce()
  })
  it('清空工时允许保存，正工时仍需配置计量字段', async () => {
    const { save } = await mount(entry())
    await input('[aria-label="标准工时（分钟）"]', '0')
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ workRule: { mode: 'RECORD_ONCE', minutes: 0 } }))
    save.mockClear()
    await input('[aria-label="标准工时（分钟）"]', '10')
    host.querySelector<HTMLInputElement>('input[value="QUANTITY"]')?.click()
    await flush()
    await click('保存配置')
    expect(save).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请选择用于计量的数量字段')
  })
  it('关闭计时不改变必办要求和授权配置', async () => {
    const original = { ...entry(), required: true, readableFieldIds: ['safe'] }
    const { save } = await mount(original)
    const toggle = host.querySelector<HTMLInputElement>('.entry-config__heading input[type="checkbox"]')!
    toggle.checked = false
    toggle.dispatchEvent(new Event('change'))
    await flush()
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ workRule: null, required: true, readableFieldIds: ['safe'] })
    )
    expect(original.workRule?.minutes).toBe(15)
  })
  it('历史已发布配置只读，不显示保存按钮', async () => {
    await mount(entry(), true)
    expect(Array.from(host.querySelectorAll('button')).some(button => button.textContent === '保存配置')).toBe(false)
    expect(host.querySelector<HTMLInputElement>('[aria-label="标准工时（小时）"]')?.disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input[value="QUANTITY"]')?.disabled).toBe(true)
  })
  it('独立数据切到指定来源默认只读，切回时恢复绑定且保留稳定key和办理要求', async () => {
    const original = { ...entry(), dataMode: 'INDEPENDENT' as const, required: true, readableFieldIds: ['room'] }
    const { save } = await mount(original)
    const select = Array.from(host.querySelectorAll('select')).find(item =>
      Array.from(item.options).some(option => option.value === 'SOURCE_SHARED')
    )!
    select.value = 'SOURCE_SHARED'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(original.binding?.viewId).toBe('room')
    select.value = 'INDEPENDENT'
    select.dispatchEvent(new Event('change'))
    await flush()
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        key: 'wifi',
        binding: original.binding,
        required: true,
        readableFieldIds: ['room'],
        writableFieldIds: []
      })
    )
  })
  it('默认固定工时只需填写时长，公式实时更新，计量说明默认收起', async () => {
    await mount(entry())
    const preview = host.querySelector('[aria-label="工时计算预览"]')
    expect(preview?.textContent).toContain('每条记录计 15 分钟')
    expect(host.querySelector<HTMLInputElement>('input[value="RECORD_ONCE"]')?.checked).toBe(true)
    expect(host.querySelector<HTMLDetailsElement>('.entry-config__notes')?.open).toBe(false)
    expect(host.querySelector('.entry-config__notes')?.textContent).toContain('不同员工分别计量')
    await input('[aria-label="标准工时（小时）"]', '3')
    await input('[aria-label="标准工时（分钟）"]', '0')
    expect(preview?.textContent).toContain('每条记录计 3 小时')
    expect(host.textContent).toContain('完成要求')
  })
  it('数量模式只展示数量字段并按所选字段预览，切换后不带入旧条件', async () => {
    const original = entry()
    original.workRule = { mode: 'CONDITION', minutes: 15, conditionFieldId: 'state', conditionValue: 'DONE' }
    const { save } = await mount(original)
    host.querySelector<HTMLInputElement>('input[value="QUANTITY"]')?.click()
    await flush()
    const field = host.querySelector<HTMLSelectElement>('[aria-label="计工时的数量字段"]')
    if (!field) throw new Error('未显示数量字段')
    expect(Array.from(field.options).map(option => option.value)).toEqual(['quantity'])
    field.value = 'quantity'
    field.dispatchEvent(new Event('change'))
    await flush()
    expect(host.querySelector('[aria-label="工时计算预览"]')?.textContent).toContain('设备数量 × 15 分钟')
    expect(host.querySelector('[aria-label="计工时的条件字段"]')).toBeNull()
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ workRule: { mode: 'QUANTITY', minutes: 15, quantityFieldId: 'quantity' } })
    )
    expect(original.workRule.conditionValue).toBe('DONE')
  })
  it('条件模式预览使用选项中文名且明确首次达标，不改变原规则', async () => {
    const original = entry()
    original.workRule = { mode: 'CONDITION', minutes: 180, conditionFieldId: 'state', conditionValue: 'DONE' }
    const { save } = await mount(original)
    expect(host.querySelector('[aria-label="工时计算预览"]')?.textContent).toContain(
      '「施工状态」首次为「已完成」时，每条计 3 小时'
    )
    expect(host.querySelector('.entry-config__notes')?.textContent).toContain('之后条件变化不扣回')
    await click('保存配置')
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ workRule: original.workRule }))
  })
})
