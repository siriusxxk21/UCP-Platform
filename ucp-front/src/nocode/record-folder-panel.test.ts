// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App } from 'vue'
import Antd, { message, notification } from 'ant-design-vue'
import type { RecordFolderOpened, RecordFolderTab } from '@/types/nocode/record-folder'
import { pageActivityKey, type PageActivity } from './page-activity'
import {
  RECORD_FOLDER_LOCKED_NOTE,
  RECORD_FOLDER_LOCKED_REASON,
  RECORD_FOLDER_RAISED_CLASS,
  RECORD_FOLDER_VIEW_ONLY_NOTE,
  RECORD_FOLDER_VIEW_ONLY_REASON,
  recordFolderNoSourceCache
} from './record-folder'

const api = vi.hoisted(() => ({ open: vi.fn(), trashList: vi.fn(), restore: vi.fn() }))
/** 浏览器组件的替身：记下每个实例收到的属性、重新取列表的次数与销毁 */
const browsers = vi.hoisted(() => ({
  mounted: [] as Array<{ props: Record<string, unknown>; reloads: number; alive: boolean }>
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ recordFolders: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ token: 'tk-1' }) }))
vi.mock('@/views/drive/components/DriveFolderBrowser.vue', async () => {
  const { defineComponent, h, onBeforeUnmount } = await import('vue')
  return {
    __esModule: true,
    default: defineComponent({
      name: 'DriveFolderBrowserStub',
      inheritAttrs: false,
      props: [
        'finderId',
        'gateway',
        'storageName',
        'rootRole',
        'allowPermission',
        'allowFavorite',
        'allowSearch',
        'lockedNote',
        'lockedReason',
        'allowWrite'
      ],
      setup(props, { expose }) {
        const record = { props: props as Record<string, unknown>, reloads: 0, alive: true }
        browsers.mounted.push(record)
        onBeforeUnmount(() => (record.alive = false))
        expose({ reload: () => (record.reloads += 1) })
        return () => h('div', { class: 'browser-stub' }, String(props.storageName))
      }
    })
  }
})
import RecordFolderPanel from '@/views/nocode/application/components/RecordFolderPanel.vue'

// 没写 canWrite 时跟 writable 走（只凭记录的情形）；要测「只凭网盘权限能写」时单独给
const tab = (value: Partial<RecordFolderTab> = {}): RecordFolderTab => ({
  sourceId: '7',
  label: '合同文件',
  state: 'READY',
  writable: true,
  ...value,
  canWrite: value.canWrite ?? value.writable ?? true
})
const opened = (...tabs: RecordFolderTab[]): RecordFolderOpened => ({ tabs })

interface PanelProps {
  applicationId: string
  objectId: string
  recordId: string
  revision?: string | null
}
const apps: App[] = []
let host: HTMLElement
const resumed = ref(0)
const active = ref(true)
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(props: Partial<PanelProps> = {}) {
  const state = reactive<PanelProps>({
    applicationId: 'app',
    objectId: 'obj',
    recordId: 'rec-1',
    revision: 'r1',
    ...props
  })
  host = document.createElement('div')
  document.body.append(host)
  const Host = defineComponent({ setup: () => () => h(RecordFolderPanel, { ...state }) })
  const app = createApp(Host)
  app.use(Antd)
  // 面板活在被保活的页面里：这里给它一个可以「回到前台」的处境
  app.provide(pageActivityKey, { resumed, active, kept: true } as unknown as PageActivity)
  app.mount(host)
  apps.push(app)
  await flush()
  return state
}
const panel = () => host.querySelector<HTMLElement>('.record-folder-panel')
const alive = () => browsers.mounted.filter(item => item.alive)
const tabTitles = () => Array.from(host.querySelectorAll('.ant-tabs-tab')).map(node => node.textContent?.trim())
const LOCK_HINT = '带锁标记的不是经由这条记录放进去的，只能查看和下载。'

beforeEach(() => {
  api.open.mockReset()
  api.trashList.mockReset().mockResolvedValue([])
  api.restore.mockReset()
  browsers.mounted.length = 0
  resumed.value = 0
  active.value = true
  recordFolderNoSourceCache().clear('obj')
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

describe('表单下方的文件夹', () => {
  it('没有页签：什么都不渲染，连分割线也没有', async () => {
    api.open.mockResolvedValue(opened())
    await mount()
    expect(api.open).toHaveBeenCalledWith({ applicationId: 'app', objectId: 'obj', recordId: 'rec-1' })
    expect(panel()).toBeNull()
    expect(host.querySelector('.ant-divider')).toBeNull()
    expect(host.textContent).toBe('')
    expect(browsers.mounted).toHaveLength(0)
  })

  it('一个页签：有分割线、标题与浏览器，没有页签栏', async () => {
    api.open.mockResolvedValue(opened(tab()))
    await mount()
    expect(host.querySelector('.ant-divider')).not.toBeNull()
    expect(host.querySelector('.record-folder-panel__title')?.textContent).toBe('文件夹')
    expect(host.querySelector('.ant-tabs')).toBeNull()
    expect(alive()).toHaveLength(1)
  })

  it('多个页签：显示页签栏；一次只渲染当前页签的浏览器，切页签时销毁上一个', async () => {
    api.open.mockResolvedValue(opened(tab(), tab({ sourceId: '8', label: '本凭证文件' })))
    await mount()
    expect(tabTitles()).toEqual(['合同文件', '本凭证文件'])
    expect(alive().map(item => item.props.storageName)).toEqual(['合同文件'])
    host.querySelectorAll<HTMLElement>('.ant-tabs-tab-btn')[1].click()
    await flush()
    expect(alive().map(item => item.props.storageName)).toEqual(['本凭证文件'])
    expect(browsers.mounted[0].alive).toBe(false)
  })

  it('浏览器收到的开关与口子：不出成员权限与收藏、开搜索；能写时带「不是经由这条记录」那两句', async () => {
    api.open.mockResolvedValue(opened(tab({ writable: true })))
    await mount()
    expect(alive()[0].props).toMatchObject({
      finderId: 'record-folder-obj-7',
      storageName: '合同文件',
      rootRole: 'EDITOR',
      allowPermission: false,
      allowFavorite: false,
      allowSearch: true,
      allowWrite: true,
      lockedNote: RECORD_FOLDER_LOCKED_NOTE,
      lockedReason: RECORD_FOLDER_LOCKED_REASON
    })
  })

  it('只读页签：关掉所有写入口，锁说明换成「你只能查看这条记录的文件」那两句', async () => {
    api.open.mockResolvedValue(opened(tab({ writable: false })))
    await mount()
    const [browser] = alive()
    expect(browser.props).toMatchObject({
      rootRole: 'VIEWER',
      allowWrite: false,
      lockedNote: RECORD_FOLDER_VIEW_ONLY_NOTE,
      lockedReason: RECORD_FOLDER_VIEW_ONLY_REASON
    })
    // 上方那句「带锁标记的不是经由这条记录放进去的」只在能写时出
    expect(panel()?.querySelector('.record-folder-panel__note')).toBeNull()
    // 「最近删除」只为恢复而设，恢复也是写：不出
    expect(
      Array.from(panel()?.querySelectorAll('button') ?? []).map(node => node.textContent?.replace(/\s/g, ''))
    ).not.toContain('最近删除')
    const gateway = browser.props.gateway as { upload: { fields: (parentId: number) => object; check?: () => void } }
    // 组件不看开关的入口（键盘删除、拖入文件）由口子先拦
    expect(() => gateway.upload.check?.()).toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    expect(gateway.upload.fields(0)).toEqual({
      applicationId: 'app',
      objectId: 'obj',
      recordId: 'rec-1',
      sourceId: '7',
      parentId: '0'
    })
  })

  it('还没选关联：显示服务端给的那句话，不渲染浏览器，页签带灰色样式', async () => {
    api.open.mockResolvedValue(
      opened(
        tab({ state: 'RELATION_EMPTY', writable: false, message: '请先选择「所属合同」' }),
        tab({ sourceId: '8', label: '共用资料' })
      )
    )
    await mount()
    expect(panel()?.textContent).toContain('请先选择「所属合同」')
    expect(alive()).toHaveLength(0)
    expect(host.querySelectorAll('.record-folder-panel__tab--dimmed')).toHaveLength(1)
    expect(panel()?.textContent).not.toContain('最近删除')
  })

  it('只读且还没建：显示「还没有文件」', async () => {
    api.open.mockResolvedValue(opened(tab({ state: 'PENDING', writable: false })))
    await mount()
    expect(panel()?.textContent).toContain('还没有文件')
    expect(alive()).toHaveLength(0)
  })

  it('open 失败：整块不渲染，也不弹任何错误', async () => {
    const error = vi.spyOn(message, 'error')
    const warning = vi.spyOn(message, 'warning')
    const notify = vi.spyOn(notification, 'error')
    api.open.mockRejectedValue(new Error('记录不存在或不可访问'))
    await mount()
    expect(panel()).toBeNull()
    expect(host.textContent).toBe('')
    expect(error).not.toHaveBeenCalled()
    expect(warning).not.toHaveBeenCalled()
    expect(notify).not.toHaveBeenCalled()
  })

  it('修订号变了（记录保存之后）：重新 open', async () => {
    api.open.mockResolvedValue(opened(tab()))
    const state = await mount()
    expect(api.open).toHaveBeenCalledTimes(1)
    state.revision = 'r2'
    await flush()
    expect(api.open).toHaveBeenCalledTimes(2)
  })

  it('换了一条记录：重新 open，凭据跟着换', async () => {
    api.open.mockResolvedValue(opened(tab()))
    const state = await mount()
    state.recordId = 'rec-2'
    await flush()
    expect(api.open).toHaveBeenLastCalledWith({ applicationId: 'app', objectId: 'obj', recordId: 'rec-2' })
    const gateway = alive()[0].props.gateway as { upload: { fields: (parentId: number) => { recordId: string } } }
    expect(gateway.upload.fields(0).recordId).toBe('rec-2')
  })

  it('页签回到前台：当前浏览器重新取一次列表，不重新 open', async () => {
    api.open.mockResolvedValue(opened(tab()))
    await mount()
    expect(alive()[0].reloads).toBe(0)
    resumed.value += 1
    await flush()
    expect(alive()[0].reloads).toBe(1)
    expect(api.open).toHaveBeenCalledTimes(1)
  })

  it('不能改记录、但在文件夹上有网盘编辑权限：照样能写，用「不是经由这条记录」那两句', async () => {
    api.open.mockResolvedValue(opened(tab({ writable: false, canWrite: true })))
    await mount()
    expect(alive()[0].props).toMatchObject({
      rootRole: 'EDITOR',
      allowWrite: true,
      lockedNote: RECORD_FOLDER_LOCKED_NOTE,
      lockedReason: RECORD_FOLDER_LOCKED_REASON
    })
    expect(panel()?.textContent).toContain(LOCK_HINT)
    expect(panel()?.textContent).toContain('最近删除')
    const gateway = alive()[0].props.gateway as { upload: { check?: () => void } }
    expect(() => gateway.upload.check?.()).not.toThrow()
  })

  it('可写时标题下面有那行锁标记说明；只读时没有', async () => {
    api.open.mockResolvedValue(opened(tab({ writable: true })))
    await mount()
    expect(panel()?.textContent).toContain(LOCK_HINT)
    apps.splice(0).forEach(app => app.unmount())
    api.open.mockResolvedValue(opened(tab({ writable: false })))
    await mount()
    expect(panel()?.textContent).not.toContain(LOCK_HINT)
  })

  it('数据维护入口（应用编号为空）：open 不带应用编号', async () => {
    api.open.mockResolvedValue(opened())
    await mount({ applicationId: '' })
    expect(api.open).toHaveBeenCalledWith({ objectId: 'obj', recordId: 'rec-1' })
  })
})

describe('组件对话框的层级', () => {
  const raised = () => document.body.classList.contains(RECORD_FOLDER_RAISED_CLASS)

  it('显示浏览器时给 body 挂类；页面切到后台、卸载时摘掉', async () => {
    api.open.mockResolvedValue(opened(tab()))
    await mount()
    expect(raised()).toBe(true)
    active.value = false
    await flush()
    expect(raised()).toBe(false)
    active.value = true
    await flush()
    expect(raised()).toBe(true)
    apps.splice(0).forEach(app => app.unmount())
    expect(raised()).toBe(false)
  })

  it('当前页签不是浏览器（提示文字）、或没有页签：不挂', async () => {
    api.open.mockResolvedValue(opened(tab({ state: 'UNAVAILABLE', writable: false, message: '文件夹已被删除' })))
    await mount()
    expect(raised()).toBe(false)
    apps.splice(0).forEach(app => app.unmount())
    api.open.mockResolvedValue(opened())
    await mount()
    expect(raised()).toBe(false)
  })
})

describe('没配文件夹的对象不重复问', () => {
  it('同一对象 5 分钟内只 open 一次；换一条记录也不再问', async () => {
    api.open.mockResolvedValue(opened())
    const state = await mount()
    expect(api.open).toHaveBeenCalledTimes(1)
    state.recordId = 'rec-2'
    await flush()
    state.revision = 'r9'
    await flush()
    expect(api.open).toHaveBeenCalledTimes(1)
    expect(panel()).toBeNull()
  })

  it('配置保存后清掉记忆：下一次打开重新问', async () => {
    api.open.mockResolvedValue(opened())
    const state = await mount()
    recordFolderNoSourceCache().clear('obj')
    api.open.mockResolvedValue(opened(tab()))
    state.recordId = 'rec-2'
    await flush()
    expect(api.open).toHaveBeenCalledTimes(2)
    expect(alive()).toHaveLength(1)
  })

  it('有页签的对象不记：每条记录都要问', async () => {
    api.open.mockResolvedValue(opened(tab()))
    const state = await mount()
    state.recordId = 'rec-2'
    await flush()
    expect(api.open).toHaveBeenCalledTimes(2)
  })
})

describe('最近删除', () => {
  const drawer = () => document.querySelector<HTMLElement>('.ant-drawer')
  const button = (text: string, root: ParentNode = document.body) => {
    const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      node => node.textContent?.replace(/\s/g, '') === text
    )
    if (!found) throw new Error('缺少按钮：' + text)
    return found
  }

  it('列出自己删除的；恢复成功后提示、重新取列表、浏览器重新取当前目录', async () => {
    const success = vi.spyOn(message, 'success')
    api.open.mockResolvedValue(opened(tab()))
    api.trashList.mockResolvedValueOnce([
      { id: '91', spaceId: '900', parentId: 0, name: '删掉的.txt', type: 'FILE', modifiable: true, trashedAt: 1 }
    ])
    api.restore.mockResolvedValue({})
    await mount()
    button('最近删除', host).click()
    await flush()
    const credential = { applicationId: 'app', objectId: 'obj', recordId: 'rec-1', sourceId: '7' }
    expect(api.trashList).toHaveBeenCalledWith(credential)
    expect(drawer()?.textContent).toContain('删掉的.txt')
    expect(drawer()?.textContent).toContain(
      '这里只列出你自己删除的、经由这条记录放进去的内容；其它的请联系网盘管理员从回收站恢复。'
    )
    button('恢复').click()
    await flush()
    expect(api.restore).toHaveBeenCalledWith({ ...credential, id: '91' })
    expect(success).toHaveBeenCalledWith('已恢复')
    expect(api.trashList).toHaveBeenCalledTimes(2)
    expect(alive()[0].reloads).toBe(1)
  })
})
