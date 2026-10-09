// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskWorkDurationInput from '@/views/nocode/task-center/TaskWorkDurationInput.vue'
import { formatEffectiveWorkMinutes, MAX_EFFECTIVE_WORK_MINUTES, validEffectiveWorkMinutes } from './task-work-duration'
import { newTaskNode, taskNodeInput } from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'

let app: App | undefined
let host: HTMLDivElement | undefined
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('有效工作时长', () => {
  it('使用整数分钟，不受24小时钟表范围限制', () => {
    expect(formatEffectiveWorkMinutes(1505)).toBe('25 小时 5 分钟')
    expect(formatEffectiveWorkMinutes(45)).toBe('45 分钟')
    expect(formatEffectiveWorkMinutes(120)).toBe('2 小时')
    expect(formatEffectiveWorkMinutes(null)).toBe('未设置')
    expect(formatEffectiveWorkMinutes(0)).toBe('未设置')
    expect(validEffectiveWorkMinutes(MAX_EFFECTIVE_WORK_MINUTES)).toBe(true)
    for (const invalid of [-1, 1.5, Number.NaN, Infinity, MAX_EFFECTIVE_WORK_MINUTES + 1])
      expect(validEffectiveWorkMinutes(invalid)).toBe(false)
  })

  it('实例调整保留总任务参考值，不把整组时长传播给子任务', () => {
    const root = { ...newTaskNode(), rootId: 'root', id: 'root', effectiveWorkMinutes: 75 } as TaskRow
    expect(taskNodeInput(root).effectiveWorkMinutes).toBe(75)
    expect(taskNodeInput({ ...root, id: 'child', parentId: 'root' }).effectiveWorkMinutes).toBeUndefined()
  })

  it('分钟与小时可分别修改、清空；只读和非法更新不发出修改', async () => {
    const value = ref<number | null>(null)
    const disabled = ref(false)
    const input = defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
    app = createApp(() =>
      h(TaskWorkDurationInput, {
        modelValue: value.value,
        disabled: disabled.value,
        'onUpdate:modelValue': next => {
          value.value = next
        }
      })
    )
    app.component('AInputNumber', input)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    const inputs = host.querySelectorAll('input')
    const change = async (index: number, text: string) => {
      const control = inputs[index]
      if (!control) throw new Error(`缺少第 ${index + 1} 个时长输入框`)
      control.value = text
      control.dispatchEvent(new Event('input'))
      await nextTick()
    }
    await change(0, '25')
    await change(1, '35')
    expect(value.value).toBe(1535)
    await change(1, '60')
    await change(0, '-1')
    await change(0, '1.5')
    expect(value.value).toBe(1535)
    await change(0, '')
    expect(value.value).toBe(35)
    disabled.value = true
    await nextTick()
    await change(1, '5')
    expect(value.value).toBe(35)
    disabled.value = false
    await nextTick()
    await change(1, '')
    expect(value.value).toBeNull()
  })
})
