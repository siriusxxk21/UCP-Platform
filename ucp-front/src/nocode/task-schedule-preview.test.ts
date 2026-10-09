// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import TaskSchedulePreview from '@/views/nocode/task-center/TaskSchedulePreview.vue'
import type { TaskSchedulePreviewNode } from '@/types/nocode/task-center'
import { newAutoTaskNode } from './task-center'

vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h('table', [
          h(
            'thead',
            h(
              'tr',
              props.columns.map((column: { title: string }) => h('th', column.title))
            )
          ),
          h(
            'tbody',
            props.dataSource.map((record: TaskSchedulePreviewNode) =>
              h(
                'tr',
                { 'data-id': record.id },
                props.columns.map((column: { key: string }) =>
                  h('td', { 'data-column': column.key }, slots.bodyCell?.({ column, record }))
                )
              )
            )
          )
        ])
  })
}))

let app: App | undefined
let host: HTMLDivElement
afterEach(() => {
  app?.unmount()
  host?.remove()
})
function mount(dates: Array<[string | null, string | null]>) {
  const nodes = dates.map((_, index) => ({
    ...newAutoTaskNode(),
    id: String(index),
    parentId: index ? '0' : null,
    title: index ? `子任务 ${index}` : '总任务'
  }))
  const preview = {
    nodes: dates.map(([expectedStart, expectedEnd], index) => ({
      id: String(index),
      title: nodes[index]?.title || '',
      expectedStart,
      expectedEnd,
      partial: false,
      warnings: []
    })),
    warnings: []
  }
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(TaskSchedulePreview, { nodes, preview })
  app.component('AAlert', defineComponent({ render: () => null }))
  app.mount(host)
  return Array.from(host.querySelectorAll('[data-column="durationDays"]')).map(cell => cell.textContent?.trim())
}

it('预览新增工期列，总任务按日期跨度显示，不误用自身默认工期或累加下级', () => {
  const durations = mount([
    ['2026-10-07', '2026-10-15'],
    ['2026-10-07', '2026-10-09'],
    ['2026-10-09', '2026-10-12'],
    ['2026-10-12', '2026-10-15'],
    ['2026-10-07', '2026-10-10']
  ])
  expect(host.querySelector('thead')?.textContent).toContain('工期（天）')
  expect(durations).toEqual(['8', '2', '3', '3', '3'])
})

it('自然日跨度保留同日零天，缺失、无效或倒置日期不伪造工期', () => {
  expect(
    mount([
      ['2026-10-07T09:00:00', '2026-10-07T18:00:00'],
      [null, '2026-10-09'],
      ['2026-10-07', null],
      [null, null],
      ['invalid', '2026-10-09'],
      ['2026-10-09', '2026-10-07'],
      ['2026-10-07T18:00:00', '2026-10-09T09:00:00']
    ])
  ).toEqual(['0', '—', '—', '—', '—', '—', '2'])
})
