// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import TaskHierarchyCell from '@/views/nocode/task-center/TaskHierarchyCell.vue'
import type { TaskHierarchyItem } from './task-hierarchy'

let app: App | undefined
let host: HTMLDivElement
const item: TaskHierarchyItem = {
  depth: 1,
  outline: '1.1',
  label: '子任务',
  parentTitle: '施工',
  missingParent: false,
  childCount: 0
}
function mount(overrides: Partial<TaskHierarchyItem> = {}, personal = true) {
  app = createApp(() =>
    h(
      TaskHierarchyCell,
      { item: { ...item, ...overrides }, personal, compact: true },
      { default: () => h('button', '准备开始') }
    )
  )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务名称与子任务标签同行布局', () => {
  it('子任务标签独立放在名称右侧，保留层级导线而不在名称下方占行', () => {
    mount()
    const content = host.querySelector('.task-hierarchy__content')
    const side = host.querySelector('.task-hierarchy__footer')
    expect(content?.textContent).toBe('准备开始')
    expect(side?.textContent).toBe('子任务')
    expect(content?.nextElementSibling).toBe(side)
    expect(side?.parentElement).toBe(host.querySelector('.task-hierarchy__node'))
    expect(host.querySelectorAll('.task-hierarchy__guide')).toHaveLength(1)
  })

  it('总任务不显示小标签，深层子任务仍保留完整缩进', () => {
    mount({ depth: 0, label: '总任务', parentTitle: '' })
    expect(host.querySelector('.task-hierarchy__footer')).toBeNull()
    expect(host.querySelector('.task-hierarchy__label')).toBeNull()
    app?.unmount()
    host.remove()
    mount({ depth: 3 })
    expect(host.querySelectorAll('.task-hierarchy__guide')).toHaveLength(3)
    expect(host.querySelector('.task-hierarchy__footer')?.textContent).toBe('子任务')
  })

  it('不改动编排编辑器的非个人模式层级标签', () => {
    mount({}, false)
    expect(host.querySelector('.task-hierarchy__meta .task-hierarchy__label')?.textContent).toBe('1 级子任务')
    expect(host.querySelector('.task-hierarchy__footer')).toBeNull()
  })
})
