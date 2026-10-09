// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Antd, { message } from 'ant-design-vue'
import type {
  RecordFolderBackfillResult,
  RecordFolderCandidate,
  RecordFolderNameField,
  RecordFolderSource
} from '@/types/nocode/record-folder'
import { recordFolderNoSourceCache } from './record-folder'

const api = vi.hoisted(() => ({
  config: vi.fn(),
  candidates: vi.fn(),
  nameFields: vi.fn(),
  saveConfig: vi.fn(),
  backfill: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ recordFolders: api }) }))
// 文件夹选择器要连网盘接口，这里不测它：换成一个空壳
vi.mock('@/views/nocode/components/DriveFolderPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { __esModule: true, default: defineComponent({ name: 'DriveFolderPickerStub', render: () => h('span') }) }
})
import RecordFolderDesigner from '@/views/nocode/components/RecordFolderDesigner.vue'

const folder = (value: Partial<RecordFolderSource> = {}): RecordFolderSource => ({
  id: '1',
  objectId: 'obj',
  kind: 'FOLDER',
  placement: 'RECORD_SUBFOLDER',
  label: '合同文件',
  spaceId: '900',
  entryId: '11',
  createMode: 'ON_FIRST_WRITE',
  nameTemplate: null,
  displayLabel: '合同文件',
  folderPath: '业务档案 / 合同资料',
  problem: null,
  ...value
})
const relation = (value: Partial<RecordFolderSource> = {}): RecordFolderSource => ({
  id: '2',
  objectId: 'obj',
  kind: 'RELATION',
  placement: 'DIRECT',
  label: '合同文件夹',
  relationFieldId: 'f-contract',
  targetSourceId: '9',
  createMode: 'ON_FIRST_WRITE',
  nameTemplate: null,
  relationName: '所属合同',
  targetObjectName: '合同',
  targetLabel: '合同文件',
  problem: null,
  ...value
})
const fields: RecordFolderNameField[] = [
  { fieldId: 'f1', name: '合同编号', type: 'AUTO_NUMBER' },
  { fieldId: 'f2', name: '合同名称', type: 'TEXT' }
]
const candidates: RecordFolderCandidate[] = [
  {
    relationFieldId: 'f-contract',
    relationName: '所属合同',
    targetObjectId: 'c',
    targetObjectName: '合同',
    sources: [{ id: '9', label: '合同文件' }],
    disabledReason: null
  }
]
const page = (value: Partial<RecordFolderBackfillResult>): RecordFolderBackfillResult => ({
  cursor: null,
  done: false,
  scanned: 0,
  created: 0,
  existing: 0,
  skipped: 0,
  failed: 0,
  failures: [],
  ...value
})

const apps: App[] = []
let host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(props: { objectId?: string; canManage?: boolean } = { objectId: 'obj', canManage: true }) {
  const state = reactive({ objectId: props.objectId, canManage: props.canManage ?? true })
  host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ setup: () => () => h(RecordFolderDesigner, { ...state }) })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  await flush()
  return state
}
const text = (root: ParentNode = host) => (root.textContent || '').replace(/\s+/g, ' ')
const rows = () => Array.from(host.querySelectorAll<HTMLElement>('.designer-row'))
const button = (label: string, root: ParentNode = host) => {
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    node => node.textContent?.replace(/\s/g, '') === label.replace(/\s/g, '')
  )
  if (!found) throw new Error('缺少按钮：' + label)
  return found
}
const radio = (label: string, root: ParentNode) => {
  const found = Array.from(root.querySelectorAll<HTMLLabelElement>('label.ant-radio-wrapper')).find(
    node => node.textContent?.trim() === label
  )
  if (!found) throw new Error('缺少选项：' + label)
  return found.querySelector<HTMLInputElement>('input')!
}
async function choose(label: string, root: ParentNode) {
  radio(label, root).click()
  await flush()
}
const backfillModal = () => {
  const found = Array.from(document.querySelectorAll<HTMLElement>('.ant-modal')).find(node =>
    node.textContent?.includes('已处理')
  )
  if (!found) throw new Error('补建进度框未打开')
  return found
}

beforeEach(() => {
  Object.values(api).forEach(fn => fn.mockReset())
  api.config.mockResolvedValue([folder(), relation()])
  api.candidates.mockResolvedValue(candidates)
  api.nameFields.mockResolvedValue(fields)
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('对象的文件夹设置', () => {
  it('对象还没保存：提示先保存对象，不取数，没有任何可操作的入口', async () => {
    await mount({ objectId: undefined })
    expect(text()).toContain('请先保存对象')
    expect(api.config).not.toHaveBeenCalled()
    expect(host.querySelectorAll('button')).toHaveLength(0)
  })

  it('标题、副标题与两类来源各自的放法文案', async () => {
    await mount()
    expect(api.config).toHaveBeenCalledWith('obj')
    expect(text()).toContain('表单下方按这里的顺序显示页签；只有一个时不显示页签栏。保存后立即生效，不需要发布。')
    const [first, second] = rows()
    expect(text(first)).toContain('业务档案 / 合同资料')
    expect(text(first)).toContain('每条记录一个子文件夹')
    expect(text(first)).toContain('所有记录共用这个文件夹')
    expect(text(second)).toContain('所属合同 → 合同文件')
    expect(text(second)).toContain('直接放进关联记录的文件夹')
    expect(text(second)).toContain('在其中为本记录建一个子文件夹')
    for (const label of ['保存文件夹设置', '指定网盘里的文件夹', '用关联记录的文件夹'])
      expect(button(label)).toBeTruthy()
  })

  it('放法是「子文件夹」才有 建立时机 / 名称 / 补建 三行；另一种放法没有', async () => {
    await mount()
    const [first, second] = rows()
    for (const line of ['什么时候建', '子文件夹名称', '为已有记录补建文件夹']) {
      expect(text(first)).toContain(line)
      expect(text(second)).not.toContain(line)
    }
    await choose('所有记录共用这个文件夹', first)
    expect(text(rows()[0])).not.toContain('什么时候建')
    await choose('在其中为本记录建一个子文件夹', rows()[1])
    expect(text(rows()[1])).toContain('什么时候建')
  })

  it('选「记录保存时就建」时给出那句提示', async () => {
    await mount()
    const hint = '每条记录都会有一个子文件夹；记录很多时，在网盘里打开上一层文件夹会比较慢。'
    expect(text()).not.toContain(hint)
    await choose('记录保存时就建', rows()[0])
    expect(text(rows()[0])).toContain(hint)
  })

  it('自己组合名称：加字段、加固定文字，示例跟着变；已选的字段不再可选', async () => {
    api.config.mockResolvedValue([
      folder({
        nameTemplate: {
          separator: '-',
          parts: [
            { kind: 'FIELD', fieldId: 'f1' },
            { kind: 'TEXT', text: '资料' }
          ]
        }
      })
    ])
    await mount()
    const [first] = rows()
    expect(text(first)).toContain('示例：合同编号-资料')
    expect(Array.from(first.querySelectorAll('.designer-part__label')).map(node => node.textContent)).toEqual([
      '合同编号',
      '资料'
    ])
    expect(text(first)).toContain(
      '记录建好以后再改这些字段，文件夹不会跟着改名。名称里的 / 和 \\ 会换成空格；重名时自动加 (2)。'
    )
    // 把固定文字挪到前面
    first.querySelector<HTMLButtonElement>('.designer-part:nth-of-type(2) button[aria-label="前移"]')!.click()
    await flush()
    expect(text(rows()[0])).toContain('示例：资料-合同编号')
    // 用回记录名称
    await choose('用记录名称', rows()[0])
    expect(text(rows()[0])).not.toContain('示例：')
  })

  it('保存：只提交服务端接受的字段，成功后提示并清掉「没有文件夹」的记忆', async () => {
    const success = vi.spyOn(message, 'success')
    recordFolderNoSourceCache().mark('obj', 'app')
    api.saveConfig.mockResolvedValue([folder({ placement: 'DIRECT' }), relation()])
    await mount()
    expect(text()).not.toContain('有未保存的修改')
    await choose('所有记录共用这个文件夹', rows()[0])
    expect(text()).toContain('有未保存的修改')
    button('保存文件夹设置').click()
    await flush()
    expect(api.saveConfig).toHaveBeenCalledTimes(1)
    const [objectId, sources] = api.saveConfig.mock.calls[0]
    expect(objectId).toBe('obj')
    expect(sources[0]).toEqual({
      id: '1',
      kind: 'FOLDER',
      placement: 'DIRECT',
      label: '合同文件',
      spaceId: '900',
      entryId: '11',
      relationFieldId: null,
      targetSourceId: null,
      createMode: null,
      nameTemplate: null
    })
    expect(sources[1]).not.toHaveProperty('relationName')
    expect(success).toHaveBeenCalledWith('文件夹设置已保存')
    expect(recordFolderNoSourceCache().has('obj', 'app')).toBe(false)
    expect(text()).not.toContain('有未保存的修改')
  })

  it('保存失败：显示服务端文案，改动还在', async () => {
    const error = vi.spyOn(message, 'error')
    api.saveConfig.mockRejectedValue(new Error('你在网盘里没有管理这个文件夹的权限'))
    await mount()
    await choose('记录保存时就建', rows()[0])
    button('保存文件夹设置').click()
    await flush()
    expect(error).toHaveBeenCalledWith('你在网盘里没有管理这个文件夹的权限')
    expect(text()).toContain('有未保存的修改')
  })

  it('前端就能发现的问题：不提交，直接指出', async () => {
    const error = vi.spyOn(message, 'error')
    api.config.mockResolvedValue([folder(), folder({ id: '3', label: '另一个' })])
    await mount()
    expect(text()).toContain('同一个文件夹同一种放法不能添加两次')
    button('保存文件夹设置').click()
    await flush()
    expect(api.saveConfig).not.toHaveBeenCalled()
    expect(error).toHaveBeenCalledWith('同一个文件夹同一种放法不能添加两次')
  })

  it('上移 / 下移 / 删除改的是提交顺序', async () => {
    api.saveConfig.mockResolvedValue([])
    await mount()
    rows()[1].querySelector<HTMLButtonElement>('button[aria-label="上移"]')!.click()
    await flush()
    button('删除', rows()[1]).click()
    await flush()
    button('保存文件夹设置').click()
    await flush()
    expect(api.saveConfig.mock.calls[0][1].map((item: { id: string }) => item.id)).toEqual(['2'])
  })

  it('没有管理权：只读，没有保存、添加、补建入口', async () => {
    await mount({ objectId: 'obj', canManage: false })
    expect(rows()).toHaveLength(2)
    for (const label of ['保存文件夹设置', '指定网盘里的文件夹', '用关联记录的文件夹', '为已有记录补建文件夹', '更换'])
      expect(() => button(label)).toThrow()
    expect(Array.from(host.querySelectorAll<HTMLInputElement>('input')).every(input => input.disabled)).toBe(true)
  })

  it('配置已失效的行标出原因', async () => {
    api.config.mockResolvedValue([folder({ problem: '文件夹已被删除' })])
    await mount()
    expect(host.querySelector('.designer-row__problem')?.textContent).toBe('文件夹已被删除')
  })

  it('读取失败：显示原因，可以重试', async () => {
    api.config.mockRejectedValueOnce(new Error('数据对象不存在或尚未发布'))
    await mount()
    expect(text()).toContain('数据对象不存在或尚未发布')
    expect(() => button('保存文件夹设置')).toThrow()
    button('重试').click()
    await flush()
    expect(rows()).toHaveLength(2)
  })
})

describe('为已有记录补建文件夹', () => {
  it('有未保存的修改时不能补建', async () => {
    await mount()
    expect(button('为已有记录补建文件夹').disabled).toBe(false)
    await choose('记录保存时就建', rows()[0])
    expect(button('为已有记录补建文件夹').disabled).toBe(true)
  })

  it('进度框累计数字；结束后按钮变成关闭，列出失败与跳过的说明', async () => {
    api.backfill
      .mockResolvedValueOnce(page({ cursor: 'c1', scanned: 100, created: 90, existing: 10 }))
      .mockResolvedValueOnce(
        page({
          cursor: 'c2',
          scanned: 50,
          created: 40,
          skipped: 8,
          failed: 2,
          failures: [{ recordId: 'r-7', message: '网盘暂时不可用' }],
          done: true
        })
      )
    await mount()
    button('为已有记录补建文件夹').click()
    await flush()
    const modal = backfillModal()
    expect(text(modal)).toContain('为已有记录补建文件夹')
    expect(modal.querySelector('.backfill-summary')?.textContent).toBe(
      '已处理 150 条　新建 130　已有 10　跳过 8　失败 2'
    )
    expect(text(modal)).toContain('跳过的是还没有选关联、或文件夹当前不可用的记录。')
    expect(text(modal)).toContain('r-7：网盘暂时不可用')
    expect(() => button('停止', modal)).toThrow()
    expect(api.backfill.mock.calls.map(call => call[0])).toEqual([
      { objectId: 'obj', sourceId: '1', cursor: null, limit: 100 },
      { objectId: 'obj', sourceId: '1', cursor: 'c1', limit: 100 }
    ])
    button('关闭', modal).click()
    await flush()
  })

  it('请求失败：已经累计的数字留着，并显示原因，框不消失', async () => {
    api.backfill
      .mockResolvedValueOnce(page({ cursor: 'c1', scanned: 100, created: 70, existing: 30 }))
      .mockRejectedValueOnce(new Error('需要数据对象管理权限'))
    await mount()
    button('为已有记录补建文件夹').click()
    await flush()
    const modal = backfillModal()
    expect(modal.querySelector('.backfill-summary')?.textContent).toContain('已处理 100 条　新建 70　已有 30')
    expect(text(modal)).toContain('需要数据对象管理权限')
    expect(button('关闭', modal)).toBeTruthy()
  })

  it('跑到一半点停止：这一页结束后停下，再次补建从头开始', async () => {
    let release!: (value: RecordFolderBackfillResult) => void
    api.backfill
      .mockResolvedValueOnce(page({ cursor: 'c1', scanned: 100, created: 100 }))
      .mockImplementationOnce(() => new Promise(resolve => (release = resolve)))
    await mount()
    button('为已有记录补建文件夹').click()
    await flush()
    const modal = backfillModal()
    button('停止', modal).click()
    release(page({ cursor: 'c2', scanned: 100, created: 100 }))
    await flush()
    expect(api.backfill).toHaveBeenCalledTimes(2)
    expect(modal.querySelector('.backfill-summary')?.textContent).toContain('已处理 200 条')
    button('关闭', modal).click()
    await flush()
    api.backfill.mockResolvedValueOnce(page({ cursor: 'c9', scanned: 3, existing: 3, done: true }))
    button('为已有记录补建文件夹').click()
    await flush()
    expect(api.backfill.mock.calls.at(-1)?.[0]).toEqual({ objectId: 'obj', sourceId: '1', cursor: null, limit: 100 })
    expect(backfillModal().querySelector('.backfill-summary')?.textContent).toContain('已处理 3 条　新建 0　已有 3')
  })
})
