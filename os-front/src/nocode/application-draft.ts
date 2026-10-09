import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import type { ApplicationApi } from '@/api/nocode/application'
import type { ApplicationDetail, ApplicationStatus, SaveApplication } from '@/types/nocode/application'
import { errorMessage } from './data-center'
import { createRequestSession } from './request-session'

type DraftApi = Pick<ApplicationApi, 'get' | 'save' | 'restore' | 'status'>
type Submission =
  | { kind: 'save' }
  | { kind: 'restore'; sourceVersion: number; reason: string }
  | { kind: 'status'; status: ApplicationStatus; reason: string }

export type ApplicationDraftResult =
  { kind: 'accepted'; unchanged: boolean } | { kind: 'failed'; message: string } | { kind: 'ignored' }

const unloadedMessage = '应用配置尚未加载成功，请先重试加载，避免保存不完整配置。'
const emptyDraft = (id: string): SaveApplication => ({
  id,
  expectedRevision: null,
  code: '',
  name: '',
  category: '',
  description: null,
  icon: null,
  definition: { objects: [], resources: [] }
})

/** 管理单个应用的草稿与修订会话；路由、权限、对象目录和操作反馈由工作台负责。 */
export function useApplicationDraft(applicationId: () => string, api: DraftApi) {
  const detail = ref<ApplicationDetail>(),
    draft = reactive(emptyDraft(applicationId())),
    dirty = ref(false),
    error = ref(''),
    loading = ref(false),
    submitting = ref(false)
  const loaded = computed(() => detail.value?.application.id === applicationId())
  const loadSession = createRequestSession(),
    submitSession = createRequestSession(),
    publishSession = createRequestSession()
  let alive = true
  let publication: { submitted: string; current: () => boolean } | undefined

  function invalidate() {
    loadSession.invalidate()
    submitSession.invalidate()
    publishSession.invalidate()
    publication = undefined
    loading.value = false
    submitting.value = false
  }
  watch(
    applicationId,
    id => {
      // 切换应用立即释放旧界面的提交状态；旧 finally 不能解除新应用的提交锁。
      invalidate()
      detail.value = undefined
      Object.assign(draft, emptyDraft(id))
      dirty.value = false
      error.value = ''
    },
    { flush: 'sync' }
  )
  onScopeDispose(() => {
    alive = false
    invalidate()
  })

  function accept(value: ApplicationDetail) {
    detail.value = value
    Object.assign(draft, {
      id: value.application.id,
      expectedRevision: value.application.revision,
      code: value.application.code,
      name: value.application.name,
      category: value.application.category ?? '',
      description: value.application.description,
      icon: value.application.icon,
      definition: JSON.parse(JSON.stringify(value.draft))
    })
    dirty.value = false
  }
  function acceptSubmitted(value: ApplicationDetail, submitted: string): ApplicationDraftResult {
    const unchanged = JSON.stringify(draft) === submitted
    if (unchanged) accept(value)
    else {
      // 资源 ID 由客户端稳定分配；继续输入时只推进修订号，完整配置留待下一次保存。
      detail.value = value
      draft.expectedRevision = value.application.revision
      dirty.value = true
    }
    return { kind: 'accepted', unchanged }
  }
  async function load(): Promise<boolean> {
    if (!alive || submitting.value) return false
    const request = loadSession.begin(),
      requested = applicationId()
    const current = () => alive && request() && applicationId() === requested
    loading.value = true
    error.value = ''
    try {
      const result = await api.get(requested)
      if (!current() || result.application.id !== requested) return false
      accept(result)
      return true
    } catch (cause) {
      if (current()) error.value = errorMessage(cause)
      return false
    } finally {
      if (current()) loading.value = false
    }
  }
  async function submit(command: Submission): Promise<ApplicationDraftResult> {
    if (!alive || submitting.value) return { kind: 'ignored' }
    if (command.kind === 'save') error.value = ''
    const application = detail.value?.application
    if (!loaded.value || loading.value || !application) {
      if (command.kind === 'save') error.value = unloadedMessage
      return { kind: 'failed', message: unloadedMessage }
    }
    const request = submitSession.begin(),
      requested = applicationId(),
      submitted = JSON.stringify(draft)
    // 草稿本身已按 SaveApplication 构建；JSON 副本遵循 API 持久化协议，也解除嵌套 Vue 代理。
    const snapshot: SaveApplication = JSON.parse(submitted)
    const current = () => alive && request() && applicationId() === requested
    submitting.value = true
    try {
      const identity = { id: requested, expectedRevision: application.revision }
      const result = await (command.kind === 'save'
        ? api.save(snapshot)
        : command.kind === 'restore'
          ? api.restore({ ...identity, sourceVersion: command.sourceVersion, reason: command.reason })
          : api.status({ ...identity, status: command.status, reason: command.reason }))
      if (!current() || result.application.id !== requested) return { kind: 'ignored' }
      return acceptSubmitted(result, submitted)
    } catch (cause) {
      if (!current()) return { kind: 'ignored' }
      const message = errorMessage(cause)
      if (command.kind === 'save') error.value = message
      return { kind: 'failed', message }
    } finally {
      if (current()) submitting.value = false
    }
  }
  function capturePublish(): boolean {
    if (!alive || !loaded.value || submitting.value) return false
    const request = publishSession.begin(),
      requested = applicationId()
    publication = {
      submitted: JSON.stringify(draft),
      current: () => alive && request() && applicationId() === requested
    }
    return true
  }
  function acceptPublished(value: ApplicationDetail): ApplicationDraftResult {
    if (!publication?.current() || value.application.id !== applicationId()) return { kind: 'ignored' }
    const result = acceptSubmitted(value, publication.submitted)
    publication = undefined
    return result
  }

  return {
    detail: computed(() => detail.value),
    // 服务端差异属于读取时的固定版本；本地同步引用后不能继续拿旧版本提示代表当前草稿。
    objectIssues: computed(() =>
      (detail.value?.issues || []).filter(issue =>
        draft.definition.objects.some(ref => ref.objectId === issue.objectId && ref.versionNo === issue.versionNo)
      )
    ),
    draft,
    dirty,
    error,
    loaded,
    loading: computed(() => loading.value),
    submitting: computed(() => submitting.value),
    load,
    save: () => submit({ kind: 'save' }),
    restore: (input: { sourceVersion: number; reason: string }) => submit({ kind: 'restore', ...input }),
    changeStatus: (input: { status: ApplicationStatus; reason: string }) => submit({ kind: 'status', ...input }),
    capturePublish,
    acceptPublished
  }
}
