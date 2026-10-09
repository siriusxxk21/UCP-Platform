import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import type { ApplicationApi } from '@/api/nocode/application'
import type { ApplicationDetail, SaveApplication } from '@/types/nocode/application'
import { useApplicationDraft } from './application-draft'

const scopes: EffectScope[] = []
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function application(id = 'A', revision = 1): ApplicationDetail {
  return {
    application: {
      id,
      revision,
      code: `app_${id}`,
      name: `应用 ${id}`,
      description: null,
      icon: null,
      status: 'ACTIVE',
      publishedVersion: null,
      updateTime: ''
    },
    draft: {
      objects: [{ objectId: 'object', versionNo: 2, checksum: 'fixed-checksum' }],
      resources: [{ id: 'stable-page', kind: 'PAGE', name: '页面', code: 'page', config: { nested: { value: 1 } } }]
    },
    issues: []
  }
}
function saved(input: SaveApplication, revision: number): ApplicationDetail {
  const result = application(input.id || 'A', revision)
  return {
    ...result,
    application: {
      ...result.application,
      name: input.name,
      description: input.description,
      icon: input.icon,
      category: input.category
    },
    draft: copy(input.definition)
  }
}
function deferred<T>() {
  const handlers = {
    resolve: vi.fn<(value: T) => void>(),
    reject: vi.fn<(reason: unknown) => void>()
  }
  const promise = new Promise<T>((resolve, reject) => {
    handlers.resolve.mockImplementation(resolve)
    handlers.reject.mockImplementation(reject)
  })
  return { promise, ...handlers }
}
function setup() {
  const id = ref('A')
  const api = {
    get: vi.fn<ApplicationApi['get']>().mockImplementation(async requested => application(requested)),
    save: vi.fn<ApplicationApi['save']>().mockImplementation(async input => saved(input, 2)),
    restore: vi.fn<ApplicationApi['restore']>(),
    status: vi.fn<ApplicationApi['status']>()
  }
  const scope = effectScope()
  scopes.push(scope)
  const state = scope.run(() => useApplicationDraft(() => id.value, api))
  if (!state) throw new Error('测试作用域未启动')
  return { id, api, state, scope }
}
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()))

describe('应用草稿与修订会话', () => {
  it('本地同步对象版本后移除旧版本差异提示，保存失败仍保留当前引用和其他对象提示', async () => {
    const { state, api } = setup()
    const server = application()
    server.draft.objects.push({ objectId: 'other', versionNo: 1, checksum: 'other-v1' })
    server.issues = [
      { objectId: 'object', objectName: '流水', versionNo: 2, latestVersionNo: 3, messages: ['旧字段已移除'] },
      { objectId: 'other', objectName: '凭证', versionNo: 1, latestVersionNo: 2, messages: ['另一个变更'] }
    ]
    api.get.mockResolvedValueOnce(server)
    await state.load()
    expect(state.objectIssues.value).toHaveLength(2)
    Object.assign(state.draft.definition.objects[0]!, { versionNo: 3, checksum: 'v3' })
    api.save.mockRejectedValueOnce(new Error('表单仍引用旧字段'))
    await state.save()
    expect(state.objectIssues.value.map(issue => issue.objectId)).toEqual(['other'])
    expect(state.draft.definition.objects[0]?.versionNo).toBe(3)
    expect(state.detail.value?.issues).toHaveLength(2)
    state.draft.definition.objects[0]!.versionNo = 2
    expect(state.objectIssues.value).toHaveLength(2)
  })
  it('分类随草稿回读和普通保存保留，并允许显式清空', async () => {
    const { state, api } = setup()
    const server = application()
    server.application.category = '经营管理'
    api.get.mockResolvedValueOnce(server)
    await state.load()
    expect(state.draft.category).toBe('经营管理')
    state.draft.name = '改名'
    await state.save()
    expect(api.save.mock.calls[0]?.[0].category).toBe('经营管理')
    expect(state.draft.category).toBe('经营管理')
    state.draft.category = ''
    await state.save()
    expect(api.save.mock.calls[1]?.[0].category).toBe('')
    expect(state.draft.category).toBe('')
  })
  it('未加载成功不提交；加载后保留草稿对象身份并接受服务器规范化结果', async () => {
    const { state, api } = setup()
    const identity = state.draft
    expect((await state.save()).kind).toBe('failed')
    expect(api.save).not.toHaveBeenCalled()
    const server = application()
    api.get.mockResolvedValueOnce(server)
    expect(await state.load()).toBe(true)
    state.draft.name = '待规范化'
    state.dirty.value = true
    expect(state.draft.definition).not.toBe(server.draft)
    const response = application('A', 2)
    response.application.name = '服务器规范化名称'
    api.save.mockResolvedValueOnce(response)
    expect(await state.save()).toEqual({ kind: 'accepted', unchanged: true })
    expect(state.draft).toBe(identity)
    expect(state.draft.name).toBe('服务器规范化名称')
    expect(state.draft.expectedRevision).toBe(2)
    expect(state.dirty.value).toBe(false)
    expect(state.error.value).toBe('')
  })

  it('独立提交快照保留固定版本和完整资源配置，后续输入沿用新修订再保存', async () => {
    const { state, api } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.save.mockReturnValueOnce(pending.promise)
    state.draft.name = '首次输入'
    state.dirty.value = true
    const saving = state.save()
    const request = api.save.mock.calls[0]?.[0]
    if (!request) throw new Error('未提交应用草稿')
    const resource = state.draft.definition.resources[0]
    if (!resource) throw new Error('缺少测试页面')
    Object.assign(resource.config, { nested: { value: 9 }, futureOption: ['preserved'] })
    state.draft.name = '继续输入'
    expect(request.definition.objects).toEqual([{ objectId: 'object', versionNo: 2, checksum: 'fixed-checksum' }])
    expect(request.definition.resources[0]?.config).toEqual({ nested: { value: 1 } })
    expect(await state.save()).toEqual({ kind: 'ignored' })
    expect(api.save).toHaveBeenCalledTimes(1)
    pending.resolve(saved(request, 2))
    expect(await saving).toEqual({ kind: 'accepted', unchanged: false })
    expect(state.draft.name).toBe('继续输入')
    expect(state.draft.definition.resources[0]).toMatchObject({
      id: 'stable-page',
      config: { nested: { value: 9 }, futureOption: ['preserved'] }
    })
    expect(state.draft.expectedRevision).toBe(2)
    expect(state.dirty.value).toBe(true)
    await state.save()
    expect(api.save.mock.calls[1]?.[0].expectedRevision).toBe(2)
    expect(state.dirty.value).toBe(false)
  })

  it('保存失败保留草稿及旧修订，重试重新提交当前完整输入', async () => {
    const { state, api } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.save.mockReturnValueOnce(pending.promise)
    state.dirty.value = true
    const saving = state.save()
    state.draft.description = '失败期间的新输入'
    pending.reject(new Error('修订冲突'))
    expect(await saving).toEqual({ kind: 'failed', message: '修订冲突' })
    expect(state.submitting.value).toBe(false)
    expect(state.draft.expectedRevision).toBe(1)
    expect(state.dirty.value).toBe(true)
    await state.save()
    expect(api.save.mock.calls[1]?.[0]).toMatchObject({ expectedRevision: 1, description: '失败期间的新输入' })
    expect(state.error.value).toBe('')
  })

  it('加载A/B乱序只接受当前应用，加载中的保存受阻', async () => {
    const { state, api, id } = setup()
    const a = deferred<ApplicationDetail>(),
      b = deferred<ApplicationDetail>()
    api.get.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const loadingA = state.load()
    id.value = 'B'
    const loadingB = state.load()
    expect((await state.save()).kind).toBe('failed')
    a.resolve(application('A'))
    expect(await loadingA).toBe(false)
    expect(state.loading.value).toBe(true)
    expect(state.loaded.value).toBe(false)
    b.resolve(application('B', 4))
    expect(await loadingB).toBe(true)
    expect(state.draft.id).toBe('B')
    expect(state.draft.expectedRevision).toBe(4)
    expect(state.loading.value).toBe(false)
  })

  it('旧加载错误不污染新应用，当前加载失败允许重试', async () => {
    const { state, api, id } = setup()
    const a = deferred<ApplicationDetail>()
    api.get.mockReturnValueOnce(a.promise)
    const loadingA = state.load()
    id.value = 'B'
    api.get.mockRejectedValueOnce(new Error('B加载失败'))
    expect(await state.load()).toBe(false)
    expect(state.error.value).toBe('B加载失败')
    a.reject(new Error('旧A失败'))
    await loadingA
    expect(state.error.value).toBe('B加载失败')
    expect(await state.load()).toBe(true)
    expect(state.error.value).toBe('')
    expect(state.loaded.value).toBe(true)
  })

  it.each(['success', 'failure'])('应用切换释放旧提交锁，旧%s回调不能释放新提交锁或覆盖新应用', async outcome => {
    const { state, api, id } = setup()
    await state.load()
    const a = deferred<ApplicationDetail>(),
      b = deferred<ApplicationDetail>()
    api.save.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    state.dirty.value = true
    const savingA = state.save()
    expect(state.submitting.value).toBe(true)
    id.value = 'B'
    expect(state.submitting.value).toBe(false)
    expect(state.dirty.value).toBe(false)
    await state.load()
    state.draft.name = '新B输入'
    state.dirty.value = true
    const savingB = state.save()
    if (outcome === 'success') a.resolve(application('A', 2))
    else a.reject(new Error('旧保存失败'))
    expect(await savingA).toEqual({ kind: 'ignored' })
    expect(state.submitting.value).toBe(true)
    expect(state.draft.name).toBe('新B输入')
    expect(state.error.value).toBe('')
    const requestB = api.save.mock.calls[1]?.[0]
    if (!requestB) throw new Error('新应用不能提交')
    b.resolve(saved(requestB, 2))
    expect(await savingB).toEqual({ kind: 'accepted', unchanged: true })
    expect(state.submitting.value).toBe(false)
  })

  it('A→B→A也不能接纳上一次A会话的保存', async () => {
    const { state, api, id } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.save.mockReturnValueOnce(pending.promise)
    const saving = state.save()
    id.value = 'B'
    id.value = 'A'
    api.get.mockResolvedValueOnce(application('A', 8))
    await state.load()
    pending.resolve(application('A', 2))
    expect(await saving).toEqual({ kind: 'ignored' })
    expect(state.draft.expectedRevision).toBe(8)
  })

  it('恢复使用详情修订及指定版本，保留在途新增资源，失败留待重试', async () => {
    const { state, api } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.restore.mockReturnValueOnce(pending.promise)
    const restoring = state.restore({ sourceVersion: 3, reason: '恢复原因' })
    expect(api.restore).toHaveBeenCalledWith({ id: 'A', expectedRevision: 1, sourceVersion: 3, reason: '恢复原因' })
    state.draft.definition.resources.push({ id: 'new-form', kind: 'FORM', code: 'form', name: '表单', config: {} })
    const response = application('A', 2)
    response.application.publishedVersion = 5
    pending.resolve(response)
    expect(await restoring).toEqual({ kind: 'accepted', unchanged: false })
    expect(state.detail.value?.application.publishedVersion).toBe(5)
    expect(state.draft.definition.resources.map(resource => resource.id)).toEqual(['stable-page', 'new-form'])
    api.restore.mockRejectedValueOnce(new Error('兼容性检查失败'))
    expect(await state.restore({ sourceVersion: 3, reason: '再次恢复' })).toEqual({
      kind: 'failed',
      message: '兼容性检查失败'
    })
    expect(state.draft.expectedRevision).toBe(2)
    expect(state.submitting.value).toBe(false)
  })

  it('停启用使用最新修订并保留新输入，与保存共享互斥边界', async () => {
    const { state, api } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.status.mockReturnValueOnce(pending.promise)
    const changing = state.changeStatus({ status: 'DISABLED', reason: '维护' })
    expect(api.status).toHaveBeenCalledWith({ id: 'A', expectedRevision: 1, status: 'DISABLED', reason: '维护' })
    expect((await state.save()).kind).toBe('ignored')
    state.draft.name = '操作期间新名称'
    const response = application('A', 2)
    response.application.status = 'DISABLED'
    pending.resolve(response)
    expect(await changing).toEqual({ kind: 'accepted', unchanged: false })
    expect(state.detail.value?.application.status).toBe('DISABLED')
    expect(state.draft.name).toBe('操作期间新名称')
    expect(state.dirty.value).toBe(true)
    api.status.mockRejectedValueOnce(new Error('不能停用'))
    expect((await state.changeStatus({ status: 'ACTIVE', reason: '启用' })).kind).toBe('failed')
    expect(state.draft.expectedRevision).toBe(2)
  })

  it('发布回填保留期间新输入，同次结果只接受一次，切换应用使发布快照失效', async () => {
    const { state, id } = setup()
    await state.load()
    expect(state.capturePublish()).toBe(true)
    state.draft.name = '发布期间新输入'
    const response = application('A', 2)
    response.application.publishedVersion = 1
    expect(state.acceptPublished(response)).toEqual({ kind: 'accepted', unchanged: false })
    expect(state.draft.name).toBe('发布期间新输入')
    expect(state.draft.expectedRevision).toBe(2)
    expect(state.acceptPublished(response).kind).toBe('ignored')
    state.capturePublish()
    id.value = 'B'
    await state.load()
    expect(state.acceptPublished(response).kind).toBe('ignored')
    expect(state.draft.id).toBe('B')
  })

  it('无新编辑的发布接纳服务器草稿，错误应用响应不能消耗当前发布快照', async () => {
    const { state } = setup()
    await state.load()
    state.capturePublish()
    expect(state.acceptPublished(application('B', 2)).kind).toBe('ignored')
    const response = application('A', 2)
    response.application.publishedVersion = 1
    expect(state.acceptPublished(response)).toEqual({ kind: 'accepted', unchanged: true })
    expect(state.dirty.value).toBe(false)
    expect(state.detail.value?.application.publishedVersion).toBe(1)
  })

  it('销毁作用域后加载、保存及发布回填均无效', async () => {
    const { state, api, scope } = setup()
    await state.load()
    state.capturePublish()
    const pending = deferred<ApplicationDetail>()
    api.save.mockReturnValueOnce(pending.promise)
    const saving = state.save()
    scope.stop()
    pending.resolve(application('A', 2))
    expect((await saving).kind).toBe('ignored')
    expect(state.acceptPublished(application('A', 2)).kind).toBe('ignored')
    expect(await state.load()).toBe(false)
    expect((await state.save()).kind).toBe('ignored')
    expect(state.draft.expectedRevision).toBe(1)
  })
  it('销毁作用域使正在加载的响应失效', async () => {
    const { state, api, scope } = setup()
    const pending = deferred<ApplicationDetail>()
    api.get.mockReturnValueOnce(pending.promise)
    const loading = state.load()
    scope.stop()
    pending.resolve(application())
    expect(await loading).toBe(false)
    expect(state.detail.value).toBeUndefined()
    expect(state.loading.value).toBe(false)
  })
  it.each(['restore', 'status'])('应用切换后旧%s响应不覆盖当前草稿', async operation => {
    const { state, api, id } = setup()
    await state.load()
    const pending = deferred<ApplicationDetail>()
    api.restore.mockReturnValueOnce(pending.promise)
    api.status.mockReturnValueOnce(pending.promise)
    const submitting =
      operation === 'restore'
        ? state.restore({ sourceVersion: 2, reason: '恢复' })
        : state.changeStatus({ status: 'DISABLED', reason: '停用' })
    id.value = 'B'
    await state.load()
    pending.resolve(application('A', 2))
    expect((await submitting).kind).toBe('ignored')
    expect(state.draft.id).toBe('B')
    expect(state.draft.expectedRevision).toBe(1)
  })
})
