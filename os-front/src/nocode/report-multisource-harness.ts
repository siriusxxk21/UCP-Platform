import { defineComponent, h, inject, nextTick, provide, type App } from 'vue'

/**
 * 「多个数据来源」界面用例共用的组件桩（抄自 report-detail-grain-harness.ts，该文件不动）：
 * 多了 AInput（可输入）、ACollapse；ASelect 的选项带 title（不相容原因）、多选模式发数组。
 */
interface Choice {
  value?: string
  label: string
  disabled?: boolean
  title?: string
  options?: Choice[]
}
const radioKey = Symbol('report-multisource-radio')

export const flush = async (rounds = 5) => {
  for (let i = 0; i < rounds; i++) {
    await Promise.resolve()
    await nextTick()
  }
}

export function registerStubs(target: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.title?.(), slots.extra?.(), slots.default?.()])
  })
  for (const name of [
    'ATabs',
    'ATabPane',
    'ASpace',
    'AEmpty',
    'ACard',
    'ASpin',
    'AForm',
    'ARangePicker',
    'ACollapse',
    'ACollapsePanel'
  ])
    target.component(name, plain)
  target.component(
    'AInput',
    defineComponent({
      props: ['value', 'placeholder'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value ?? '',
            placeholder: props.placeholder,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
  )
  target.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-form-item': props.label }, slots.default?.())
    })
  )
  target.component(
    'AAlert',
    defineComponent({
      props: ['message', 'type'],
      setup: props => () => h('aside', { role: 'alert', 'data-alert-type': props.type }, props.message)
    })
  )
  target.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  target.component(
    'ASegmented',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            { 'data-segmented': true },
            props.options.map((o: Choice) =>
              h(
                'button',
                {
                  type: 'button',
                  'data-display': o.value,
                  onClick: () => {
                    emit('update:value', o.value)
                    emit('change', o.value)
                  }
                },
                o.label
              )
            )
          )
    })
  )
  target.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup(props, { emit, slots }) {
        provide(radioKey, { current: () => props.value, pick: (value: unknown) => emit('update:value', value) })
        return () => h('div', { 'data-radio-group': props.value }, slots.default?.())
      }
    })
  )
  target.component(
    'ARadio',
    defineComponent({
      props: { value: String, disabled: Boolean },
      setup(props, { slots }) {
        const group = inject<{ current: () => unknown; pick: (value: unknown) => void }>(radioKey)!
        return () =>
          h('label', { 'data-radio': props.value }, [
            h('input', {
              type: 'radio',
              value: props.value,
              checked: group.current() === props.value,
              disabled: props.disabled,
              onChange: () => group.pick(props.value)
            }),
            slots.default?.()
          ])
      }
    })
  )
  target.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'mode', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: Array.isArray(props.value) ? '' : (props.value ?? ''),
              disabled: !!props.disabled,
              onChange: (event: Event) => {
                const picked = (event.target as HTMLSelectElement).value || undefined
                const value = props.mode === 'multiple' ? [...(props.value || []), picked] : picked
                emit('update:value', value)
                emit('change', value)
              }
            },
            [
              h('option', { value: '' }, ''),
              ...(props.options || []).map((o: Choice) =>
                o.options
                  ? h(
                      'optgroup',
                      { label: o.label },
                      o.options.map(v => h('option', { value: v.value }, v.label))
                    )
                  : h('option', { value: o.value, disabled: !!o.disabled, title: o.title }, o.label)
              )
            ]
          )
    })
  )
  target.component(
    'ACheckbox',
    defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit, slots, attrs }) =>
        () =>
          h('label', attrs, [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              disabled: props.disabled,
              onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked)
            }),
            slots.default?.()
          ])
    })
  )
  target.component(
    'AInputNumber',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'number',
            value: props.value,
            onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))
          })
    })
  )
  const overlay = (attribute: string) =>
    defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', { [attribute]: props.title }, slots.default?.()) : null
    })
  target.component('ADrawer', overlay('data-drawer'))
  target.component('AModal', overlay('data-modal'))
  target.component(
    'ATable',
    defineComponent({
      props: ['dataSource', 'columns', 'rowKey'],
      setup: props => () =>
        h('table', { 'data-antd-table': true }, [
          h(
            'thead',
            h(
              'tr',
              (props.columns || []).map((column: { title: string; key: string }) =>
                h('th', { 'data-key': column.key }, column.title)
              )
            )
          ),
          h(
            'tbody',
            (props.dataSource || []).map((record: Record<string, unknown>) =>
              h(
                'tr',
                {
                  'data-row-key':
                    typeof props.rowKey === 'function' ? props.rowKey(record) : String(record[props.rowKey])
                },
                (props.columns || []).map(
                  (column: { key: string; dataIndex?: string; customRender?: (p: unknown) => unknown }) =>
                    h(
                      'td',
                      { 'data-key': column.key },
                      (column.customRender
                        ? column.customRender({ record })
                        : column.dataIndex
                          ? String(record[column.dataIndex] ?? '')
                          : '') as never
                    )
                )
              )
            )
          )
        ])
    })
  )
}

/** 下拉里的选项文字；分组选项返回「组名: 选项」。 */
export const optionLabels = (select: HTMLSelectElement) =>
  Array.from(select.querySelectorAll('option'))
    .filter(o => o.value !== '')
    .map(
      o =>
        (o.parentElement?.tagName === 'OPTGROUP' ? (o.parentElement as HTMLOptGroupElement).label + ': ' : '') +
        o.textContent
    )

export async function pick(select: HTMLSelectElement, value: string) {
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
}
