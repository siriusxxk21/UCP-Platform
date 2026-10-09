// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App } from 'vue'
import TaskSubmissionMaterial from '@/views/nocode/task-center/TaskSubmissionMaterial.vue'
import type { TaskMaterial } from '@/types/nocode/task-center'

vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: defineComponent({
    props: { record: Object, readOnly: Boolean },
    setup: props => () =>
      h('div', {
        'data-material-record': props.record?.record.id,
        'data-readonly': String(props.readOnly),
        'data-details': Object.keys(props.record?.details || {}).length
      })
  })
}))

let app: App, host: HTMLElement
afterEach(() => {
  app?.unmount()
  host?.remove()
})

function mount(withFullSnapshot: boolean) {
  const binding = { resource: { applicationId: 'app' } }
  const record = { id: 'business-record', values: { name: '房间' }, revision: 3 }
  const material = {
    entries: [
      {
        entryKey: 'wifi',
        name: 'WiFi 配置',
        binding,
        model: {},
        records: [{ id: 'fact', record, sources: [] }],
        submissions: withFullSnapshot
          ? [
              {
                contributionId: 'fact',
                binding,
                sources: [],
                record: { record, details: { equipment: [{ id: 'line' }] } }
              }
            ]
          : []
      }
    ]
  } as unknown as TaskMaterial
  app = createApp(TaskSubmissionMaterial, { material })
  app.component('AEmpty', { render: () => null })
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}

describe('交付材料快照', () => {
  it('同一贡献的主表和完整表单只展示一次，保留明细与只读状态', () => {
    mount(true)
    const snapshots = host.querySelectorAll('[data-material-record]')
    expect(snapshots).toHaveLength(1)
    expect(snapshots[0]?.getAttribute('data-details')).toBe('1')
    expect(snapshots[0]?.getAttribute('data-readonly')).toBe('true')
  })
  it('旧材料缺少完整表单快照时继续展示原主表快照', () => {
    mount(false)
    expect(host.querySelectorAll('[data-material-record]')).toHaveLength(1)
    expect(host.querySelector('[data-readonly="true"]')).not.toBeNull()
  })
})
