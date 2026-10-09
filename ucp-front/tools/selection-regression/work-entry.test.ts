import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, onBeforeUnmount } from 'vue'
import { editorCloseKey } from '@/nocode/edit-boundary'
import { workEntryKey } from '@/nocode/work-context'
import { empty, event, find, findAll, flush, mount, text } from './renderer'

const api = vi.hoisted(() => ({ application: vi.fn(), canClose: vi.fn(), loadShelf: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { id: '1' } }),
  useRouter: () => ({ replace: vi.fn() })
}))
vi.mock('ant-design-vue', () => ({
  Modal: empty,
  Drawer: defineComponent({
    props: ['open', 'title'],
    setup(props, { attrs, slots }) {
      return () => (props.open ? h('drawer', { ...attrs, title: props.title }, slots.default?.()) : null)
    }
  })
}))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/BusinessBlock.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: defineComponent({
    setup() {
      const work = inject(workEntryKey)!
      return () => h('button', { onClick: () => work.openDraft('saved-draft') }, '编辑器暂存后打开')
    }
  })
}))
vi.mock('@/views/nocode/application/components/WorkShelf.vue', () => ({
  default: defineComponent({
    emits: ['openDraft'],
    setup(_, { emit }) {
      api.loadShelf()
      return () =>
        h('work-shelf', [
          h('button', { onClick: () => emit('openDraft', 'saved-draft') }, '继续填写'),
          h('button', { onClick: () => emit('openDraft', 'submission') }, '查看材料')
        ])
    }
  })
}))
vi.mock('@/views/nocode/application/components/WorkDraftPanel.vue', () => ({
  default: defineComponent({
    props: ['id'],
    setup(props) {
      const checks = inject(editorCloseKey)!
      checks.add(api.canClose)
      onBeforeUnmount(() => checks.delete(api.canClose))
      return () => h('draft-detail', { id: props.id })
    }
  })
}))
import ApplicationRuntime from '@/views/nocode/application/runtime.vue'

const mounted: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  api.application.mockResolvedValue({
    application: { id: '1', name: '公司管理' },
    definition: { objects: [], resources: [] }
  })
  api.canClose.mockResolvedValue(true)
})
afterEach(() => mounted.splice(0).forEach(page => page.unmount()))

describe('应用页工作抽屉的切换', () => {
  it('先打开再关闭详情后，多次从列表继续填写或查看材料，始终只有一个可见容器', async () => {
    const page = mount(ApplicationRuntime)
    mounted.push(page)
    await flush()
    // 复现先显示详情、随后打开列表的顺序；原实现会保留两个抽屉门户。
    await event(
      find(page.root, 'a-button', n => text(n) === '我的草稿 / 提交记录'),
      'onClick'
    )
    await event(
      find(page.root, 'button', n => text(n) === '查看材料'),
      'onClick'
    )
    await event(find(page.root, 'drawer'), 'onClose')
    for (const label of ['继续填写', '查看材料', '继续填写']) {
      await event(
        find(page.root, 'a-button', n => text(n) === '我的草稿 / 提交记录'),
        'onClick'
      )
      const loads = api.loadShelf.mock.calls.length
      await event(
        find(page.root, 'button', n => text(n) === label),
        'onClick'
      )
      expect(findAll(page.root, n => n.type === 'drawer')).toHaveLength(1)
      expect(findAll(page.root, n => n.type === 'work-shelf')).toHaveLength(0)
      expect(find(page.root, 'draft-detail').props.id).toBe(label === '查看材料' ? 'submission' : 'saved-draft')
      expect(api.loadShelf).toHaveBeenCalledTimes(loads)
      await event(find(page.root, 'drawer'), 'onClose')
      expect(findAll(page.root, n => n.type === 'drawer')).toHaveLength(0)
    }
  })
  it('切换到详情后，共用容器仍遵守表单的未保存关闭检查', async () => {
    const page = mount(ApplicationRuntime)
    mounted.push(page)
    await flush()
    await event(
      find(page.root, 'a-button', n => text(n) === '我的草稿 / 提交记录'),
      'onClick'
    )
    await event(
      find(page.root, 'button', n => text(n) === '继续填写'),
      'onClick'
    )
    api.canClose.mockResolvedValueOnce(false)
    await event(find(page.root, 'drawer'), 'onClose')
    expect(find(page.root, 'draft-detail').props.id).toBe('saved-draft')
    expect(api.canClose).toHaveBeenCalledOnce()
    await event(find(page.root, 'drawer'), 'onClose')
    expect(findAll(page.root, n => n.type === 'drawer')).toHaveLength(0)
  })
})
