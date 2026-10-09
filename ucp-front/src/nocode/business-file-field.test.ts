// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import BusinessFileField from '@/views/nocode/application/components/BusinessFileField.vue'
import { businessSession, businessSessionId, rememberBusinessTempFile } from './business-file'
import { taskFormAccessKey } from './task-form-access'

const api = vi.hoisted(() => ({
  files: vi.fn(),
  upload: vi.fn(),
  renew: vi.fn(),
  content: vi.fn(),
  temporaryContent: vi.fn(),
  legacy: vi.fn(),
  legacyUpload: vi.fn()
}))
const userState = vi.hoisted(() => ({
  permissions: [] as string[],
  roles: [] as string[],
  userInfo: { id: 10001 },
  token: 'token-1'
}))
const pushMock = vi.hoisted(() => vi.fn())
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    bizFiles: api,
    hasPermission: (permission: string) => userState.permissions.includes(permission)
  })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => userState }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }))
vi.mock('@/utils/request', () => ({ default: { get: api.legacy, post: api.legacyUpload } }))

const policy = {
  spaceName: '采购合同',
  fixedPath: ['附件'],
  groups: [],
  recordLabelFields: [],
  fieldIds: ['f-file']
}
const sessionId = businessSessionId(10001, 'app', 'object', 'r1')

let app: App | undefined, host: HTMLDivElement | undefined, opened: string[]
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(extra: Record<string, unknown> = {}, taskScope?: string) {
  const value = ref((extra.modelValue as string[] | undefined) || [])
  app = createApp({
    render: () =>
      h(BusinessFileField, {
        image: false,
        applicationId: 'app',
        objectId: 'object',
        recordId: 'r1',
        fieldId: 'f-file',
        businessPolicy: policy,
        ...extra,
        modelValue: value.value,
        'onUpdate:modelValue': next => {
          value.value = next
        }
      })
  })
  if (taskScope)
    app.provide(taskFormAccessKey, { scope: () => taskScope, relatedFieldRules: async () => ({ results: [] }) })
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return host
}
async function chooseFiles(root: HTMLElement, ...names: string[]) {
  const input = root.querySelector<HTMLInputElement>('input[type="file"]')!
  Object.defineProperty(input, 'files', {
    configurable: true,
    value: names.map(name => new File(['upload regression'], name, { type: 'text/plain' }))
  })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  userState.permissions = []
  userState.roles = []
  opened = []
  vi.spyOn(window, 'open').mockImplementation(((url?: string | URL) => {
    opened.push(String(url))
    return {} as Window
  }) as typeof window.open)
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    if (this.classList.contains('biz-name')) this.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  let objectUrl = 0
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => `blob:business-${++objectUrl}`),
    revokeObjectURL: vi.fn()
  })
  api.files.mockResolvedValue({ list: [], total: 0 })
  api.renew.mockResolvedValue(true)
  api.content.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }))
  api.temporaryContent.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }))
  api.legacy.mockImplementation((url: string) =>
    url === '/system/user/list-all-simple'
      ? Promise.resolve([{ id: 10001, nickname: '李采购', username: 'buyer' }])
      : Promise.resolve([])
  )
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
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('业务附件控件混合回显与会话续期', () => {
  it('任务临时附件会话按节点和办理项隔离，明细预览携带字段所在区域', async () => {
    const scope = 'task:node:photo',
      scopedId = `${sessionId}:${scope}`
    const session = businessSession(sessionStorage, scopedId)
    rememberBusinessTempFile(sessionStorage, scopedId, session, {
      fileId: 'task-temp',
      name: '任务待保存.pdf',
      size: 100,
      fieldId: 'f-file'
    })
    const root = await mount({ modelValue: ['task-temp'], detailId: 'detail' }, scope)
    expect(root.textContent).toContain('任务待保存.pdf')
    root.querySelector<HTMLElement>('a.biz-name')?.click()
    await flush()
    expect(api.temporaryContent).toHaveBeenCalledWith({
      objectId: 'object',
      detailId: 'detail',
      fieldId: 'f-file',
      sessionKey: session.key,
      fileId: 'task-temp'
    })
    expect(session.key).not.toBe(businessSession(sessionStorage, sessionId).key)
    expect(session.key).not.toBe(businessSession(sessionStorage, `${sessionId}:task:other:photo`).key)
  })
  it('任务附件不展示网盘定位，即便员工另有网盘入口权限', async () => {
    userState.permissions = ['drive:business:query']
    api.files.mockResolvedValue({
      list: [{ fileId: '1', entryId: '77', name: '任务.pdf', size: 100, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    const root = await mount({ modelValue: ['1'], detailed: true }, 'task:node:photo')
    expect(root.textContent).toContain('预览')
    expect(root.textContent).toContain('下载')
    expect(root.textContent).not.toContain('在网盘中查看')
  })
  it('上传成功只保留待保存附件，不残留上传中行', async () => {
    api.upload.mockResolvedValue({ fileId: '100', name: 'success.txt', size: 17 })
    const status = vi.fn()
    const root = await mount({ recordId: undefined, 'onUpload-status': status })
    await chooseFiles(root, 'success.txt')
    expect(root.textContent).toContain('待保存')
    expect(root.textContent).not.toContain('上传中')
    expect(root.querySelectorAll('.biz-file-item')).toHaveLength(1)
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: false })
  })
  it('并发上传逐项完成后移除进度行，全部完成才解除宿主保存阻塞', async () => {
    const requests: Array<{ resolve: (value: unknown) => void; progress: (loaded: number, total: number) => void }> = []
    api.upload.mockImplementation(
      (_query, _file, options) => new Promise(resolve => requests.push({ resolve, progress: options.onProgress }))
    )
    const status = vi.fn()
    const root = await mount({ recordId: undefined, 'onUpload-status': status })
    await chooseFiles(root, 'first.txt', 'second.txt')
    expect(requests).toHaveLength(2)
    expect(status).toHaveBeenLastCalledWith({ pending: true, failed: false })
    requests[0]!.progress(5, 10)
    await flush()
    expect(root.querySelector('.ant-progress-text')?.textContent).toContain('50%')
    requests[0]!.resolve({ fileId: '101', name: 'first.txt', size: 17 })
    await flush()
    expect(root.querySelectorAll('.biz-progress')).toHaveLength(1)
    expect(status).toHaveBeenLastCalledWith({ pending: true, failed: false })
    requests[1]!.resolve({ fileId: '102', name: 'second.txt', size: 17 })
    await flush()
    expect(root.querySelectorAll('.biz-progress')).toHaveLength(0)
    expect(root.querySelectorAll('.biz-file-item')).toHaveLength(2)
    expect(root.textContent).not.toContain('上传中')
    expect(root.querySelectorAll('.ant-tag')).toHaveLength(2)
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: false })
  })
  it('失败上传立即显示重试状态，重试沿用幂等键且成功后解除阻塞', async () => {
    api.upload.mockRejectedValueOnce(new Error('验收上传失败')).mockResolvedValueOnce({ fileId: '103', size: 17 })
    const status = vi.fn()
    const root = await mount({ recordId: undefined, 'onUpload-status': status })
    await chooseFiles(root, 'retry.txt')
    expect(root.textContent).toContain('验收上传失败')
    expect(root.textContent).not.toContain('上传中')
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: true })
    Array.from(root.querySelectorAll('button'))
      .find(button => button.textContent?.includes('重试'))!
      .click()
    await flush()
    expect(api.upload).toHaveBeenCalledTimes(2)
    expect(api.upload.mock.calls[1]![0].idempotencyKey).toBe(api.upload.mock.calls[0]![0].idempotencyKey)
    expect(root.querySelectorAll('.biz-file-item')).toHaveLength(1)
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: false })
  })
  it('移除失败任务后清空失败状态，不产生附件值', async () => {
    api.upload.mockRejectedValueOnce(new Error('验收上传失败'))
    const status = vi.fn()
    const root = await mount({ recordId: undefined, 'onUpload-status': status })
    await chooseFiles(root, 'remove.txt')
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: true })
    Array.from(root.querySelectorAll('button'))
      .find(button => button.textContent?.includes('移除'))!
      .click()
    await flush()
    expect(root.querySelectorAll('.biz-file-item')).toHaveLength(0)
    expect(status).toHaveBeenLastCalledWith({ pending: false, failed: false })
  })
  it('已保存业务文件按入口与记录身份通过鉴权请求读取，并展示保存位置提示', async () => {
    api.files.mockResolvedValue({
      list: [
        {
          fileId: '9007199254740993',
          entryId: '7007199254740993',
          name: '合同.pdf',
          size: 2048,
          fieldId: 'f-file',
          recordId: 'r1'
        }
      ],
      total: 1
    })
    const root = await mount({ modelValue: ['9007199254740993'] })
    expect(api.files).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 'app',
        objectId: 'object',
        recordId: 'r1',
        detailId: undefined,
        rowId: undefined,
        fieldId: 'f-file',
        pageNo: 1,
        pageSize: 100
      })
    )
    expect(root.textContent).toContain('合同.pdf')
    expect(root.textContent).toContain('2 KB')
    expect(root.textContent).toContain('保存后归入：采购合同 / 附件')
    root.querySelector<HTMLElement>('a.biz-name')?.click()
    await flush()
    expect(api.content).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 'app',
        objectId: 'object',
        recordId: 'r1',
        fieldId: 'f-file',
        entryId: '7007199254740993'
      })
    )
    expect(opened).toHaveLength(1)
    expect(opened[0]).toBe('blob:business-1')
    expect(opened[0]).not.toContain('token')
  })
  it('本会话待保存文件续期成功后展示待保存标记，内容仍走临时上传入口', async () => {
    const session = businessSession(sessionStorage, sessionId)
    rememberBusinessTempFile(sessionStorage, sessionId, session, {
      fileId: '555',
      name: '待保存.pdf',
      size: 100,
      fieldId: 'f-file'
    })
    const root = await mount({ modelValue: ['555'] })
    expect(api.renew).toHaveBeenCalledWith(session.key)
    expect(root.textContent).toContain('待保存.pdf')
    expect(root.textContent).toContain('待保存')
    expect(root.textContent).not.toContain('上传会话已过期')
    root.querySelector<HTMLElement>('a.biz-name')?.click()
    await flush()
    expect(api.temporaryContent).toHaveBeenCalledWith({
      objectId: 'object',
      fieldId: 'f-file',
      sessionKey: session.key,
      fileId: '555'
    })
    expect(opened[0]).toBe('blob:business-1')
  })
  it('上传会话过期时给出重传提示且不再提供预览地址', async () => {
    const session = businessSession(sessionStorage, sessionId)
    rememberBusinessTempFile(sessionStorage, sessionId, session, {
      fileId: '555',
      name: '过期.pdf',
      size: 100,
      fieldId: 'f-file'
    })
    api.renew.mockResolvedValue(false)
    const root = await mount({ modelValue: ['555'] })
    expect(root.textContent).toContain('上传会话已过期')
    expect(root.textContent).toContain('过期内容不会被保留')
    expect(root.textContent).toContain('上传已过期，请移除后重新上传')
    expect(root.querySelector('a.biz-name')).toBeNull()
  })
  it('编辑保存后的业务绑定优先于残留临时缓存，不将已归档附件判为过期', async () => {
    const session = businessSession(sessionStorage, sessionId)
    rememberBusinessTempFile(sessionStorage, sessionId, session, {
      fileId: '555',
      name: '刚上传.pdf',
      size: 100,
      fieldId: 'f-file'
    })
    api.files.mockResolvedValue({
      list: [{ fileId: '555', entryId: '777', name: '刚上传.pdf', size: 100, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    // 已绑定会话不能续期；false 并不代表这份已保存文件过期。
    api.renew.mockResolvedValue(false)
    const root = await mount({ modelValue: ['555'] })
    expect(root.textContent).toContain('刚上传.pdf')
    expect(root.textContent).not.toContain('过期')
    expect(root.textContent).not.toContain('待保存')
    expect(api.renew).not.toHaveBeenCalled()
    root.querySelector<HTMLElement>('a.biz-name')!.click()
    await flush()
    expect(api.content).toHaveBeenCalledWith(expect.objectContaining({ entryId: '777', recordId: 'r1' }))
    expect(api.temporaryContent).not.toHaveBeenCalled()
  })
  it('残留已绑定缓存与真正过期临时文件并存时，仅提示未保存文件过期', async () => {
    const session = businessSession(sessionStorage, sessionId)
    for (const fileId of ['555', '556']) {
      rememberBusinessTempFile(sessionStorage, sessionId, session, {
        fileId,
        name: `${fileId}.pdf`,
        size: 100,
        fieldId: 'f-file'
      })
    }
    api.files.mockResolvedValue({
      list: [{ fileId: '555', entryId: '777', name: '555.pdf', size: 100, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    api.renew.mockResolvedValue(false)
    const root = await mount({ modelValue: ['555', '556'] })
    expect(root.textContent).toContain('有 1 个文件的上传会话已过期')
    expect(root.querySelectorAll('a.biz-name')).toHaveLength(1)
    expect(root.querySelector('a.biz-name')!.textContent).toBe('555.pdf')
    expect(root.querySelectorAll('.ant-tag')).toHaveLength(1)
  })
  it('同一字段混合展示已保存与待保存文件，互不影响', async () => {
    api.files.mockResolvedValue({
      list: [{ fileId: '1', entryId: '2', name: '已保存.pdf', size: 10, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    const session = businessSession(sessionStorage, sessionId)
    rememberBusinessTempFile(sessionStorage, sessionId, session, {
      fileId: '555',
      name: '待保存.pdf',
      size: 100,
      fieldId: 'f-file'
    })
    const root = await mount({ modelValue: ['1', '555'] })
    expect(root.textContent).toContain('已保存.pdf')
    expect(root.textContent).toContain('待保存.pdf')
    expect(root.textContent).toContain('待保存')
  })
  it('业务位置授权解析失败时不降级到普通附件接口', async () => {
    api.files.mockRejectedValue(new Error('forbidden'))
    const root = await mount({ modelValue: ['protected-file'] })
    expect(root.textContent).toContain('业务文件授权校验失败，请重试')
    expect(api.legacy).not.toHaveBeenCalledWith('/infra/file/get-list', expect.anything())
  })
  it('策略取消后仍解析历史业务文件，未纳管编号继续按普通附件显示', async () => {
    const external = await mount({ businessPolicy: null, modelValue: ['9'] })
    expect(api.files).toHaveBeenCalledWith(expect.objectContaining({ recordId: 'r1', fieldId: 'f-file' }))
    expect(external.textContent).not.toContain('保存后归入')
    expect(api.legacy).toHaveBeenCalledWith('/infra/file/get-list', { params: { ids: '9' } })
    const hidden = await mount({ hideBusinessPath: true })
    expect(hidden.textContent).not.toContain('保存后归入')
    expect(hidden.querySelector('.biz-upload-actions')).not.toBeNull()
    const readOnly = await mount({ readOnly: true })
    expect(readOnly.textContent).not.toContain('保存后归入')
    expect(readOnly.querySelector('.biz-upload-actions')).toBeNull()
  })
  it('新增记录未保存前仅按普通附件读取，不发起业务查询', async () => {
    api.legacy.mockResolvedValue([{ id: 9, name: '旧附件.doc', size: 10, configId: 3, path: 'x/y.doc' }])
    const root = await mount({ recordId: undefined, modelValue: ['9'] })
    expect(api.files).not.toHaveBeenCalled()
    expect(root.textContent).toContain('旧附件.doc')
  })
  it('记录附件增强展示业务文件的类型、上传人、时间，并提供预览、下载与网盘定位', async () => {
    userState.permissions = ['drive:business:query']
    api.files.mockResolvedValue({
      list: [
        {
          fileId: '1',
          entryId: '77',
          name: '合同.pdf',
          size: 2048,
          mimeType: 'application/pdf',
          fieldId: 'f-file',
          recordId: 'r1',
          submitter: '10001',
          uploadedAt: '2026-09-30 06:12:00'
        }
      ],
      total: 1
    })
    const root = await mount({ modelValue: ['1'], detailed: true })
    await flush()
    const meta = root.querySelector('.biz-detail-meta')
    expect(meta?.textContent).toContain('application/pdf')
    expect(meta?.textContent).toContain('2 KB')
    expect(meta?.textContent).toContain('李采购')
    expect(meta?.textContent).toContain('2026-9-30 06:12:00')
    const buttons = () => Array.from(root.querySelectorAll('button')).map(button => button.textContent?.trim())
    expect(buttons()).toEqual(expect.arrayContaining(['预览', '下载', '在网盘中查看']))
    // 下载统一先走带 Authorization 的 Blob 请求，不再把令牌放到 URL。
    root.querySelectorAll('button')[buttons().indexOf('下载')]?.click()
    await flush()
    expect(api.content).toHaveBeenCalledWith(expect.objectContaining({ entryId: '77' }))
    // 定位携带完整位置身份，交由业务文件入口重新校验
    root.querySelectorAll('button')[buttons().indexOf('在网盘中查看')]?.click()
    await flush()
    expect(pushMock).toHaveBeenCalledWith({
      path: '/drive/business',
      query: expect.objectContaining({
        applicationId: 'app',
        objectId: 'object',
        recordId: 'r1',
        fieldId: 'f-file',
        entryId: '77'
      })
    })
  })
  it('没有业务文件入口权限时保留下载但不展示网盘定位', async () => {
    api.files.mockResolvedValue({
      list: [{ fileId: '1', entryId: '77', name: '合同.pdf', size: 2048, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    const root = await mount({ modelValue: ['1'], detailed: true })
    const texts = Array.from(root.querySelectorAll('button')).map(button => button.textContent?.trim())
    expect(texts).toContain('下载')
    expect(texts).not.toContain('在网盘中查看')
    expect(pushMock).not.toHaveBeenCalled()
  })
  it('未启用增强展示时不出现业务文件的下载与定位动作', async () => {
    userState.permissions = ['drive:business:query']
    api.files.mockResolvedValue({
      list: [{ fileId: '1', entryId: '77', name: '合同.pdf', size: 2048, fieldId: 'f-file', recordId: 'r1' }],
      total: 1
    })
    const root = await mount({ modelValue: ['1'] })
    const texts = Array.from(root.querySelectorAll('button')).map(button => button.textContent?.trim())
    expect(texts).not.toContain('下载')
    expect(texts).not.toContain('在网盘中查看')
  })
})
