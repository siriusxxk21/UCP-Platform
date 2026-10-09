// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, reactive, ref, type App } from 'vue'
import type { TaskCenterApi } from '@/api/nocode/task-center'
import type { TaskAction, TaskDetail } from '@/types/nocode/task-center'
import { useTaskTransitionRecovery } from './task-transition-recovery'

const state = vi.hoisted(() => ({ user: null as unknown, guard: null as unknown }))
vi.mock('@/stores/user', () => ({ useUserStore: () => state.user }))
vi.mock('./unsaved', () => ({
  useUnsavedNavigation: (guard: () => boolean) => {
    state.guard = guard
  }
}))
let app: App, host: HTMLDivElement
const taskId = ref('task')
const command = { id: 'task', expectedRevision: 1, action: 'COMPLETE' as const, note: '原始说明', requestKey: 'key' }
let recovery: ReturnType<typeof useTaskTransitionRecovery>
const api = { transitionRecovery: vi.fn() } as unknown as TaskCenterApi
function mount() {
  host = document.createElement('div')
  app = createApp({
    setup: () => {
      recovery = useTaskTransitionRecovery(api, () => taskId.value)
      return () => null
    }
  })
  app.mount(host)
}
beforeEach(() => {
  vi.mocked(api.transitionRecovery).mockReset()
  sessionStorage.clear()
  state.user = reactive({ userInfo: { id: 'first' } })
  taskId.value = 'task'
  mount()
})
afterEach(() => {
  app.unmount()
  host.remove()
  vi.restoreAllMocks()
})

describe('任务待确认命令会话恢复', () => {
  it.each<TaskAction>(['APPROVE', 'REJECT', 'PAUSE', 'RESUME'])('状态操作 %s 刷新后仍恢复完整原请求', action => {
    recovery.begin({ ...command, action })
    expect(recovery.needsConfirmation.value).toBe(false)
    app.unmount()
    host.remove()
    mount()
    expect(recovery.pending.value).toEqual({ ...command, action })
    expect(recovery.needsConfirmation.value).toBe(true)
  })
  it('保存的是不可变命令快照，不随输入对象后续修改', () => {
    const input = { ...command }
    const attempt = recovery.begin(input)
    input.note = '后来修改的备注'
    input.expectedRevision = 9
    expect(attempt.command).toEqual(command)
    expect(recovery.pending.value).toEqual(command)
    expect(recovery.needsConfirmation.value).toBe(false)
    recovery.succeed(attempt)
    expect(recovery.pending.value).toBeNull()
    expect(recovery.needsConfirmation.value).toBe(false)
  })
  it('首次明确业务拒绝可释放命令，未知结果不会据普通错误释放', async () => {
    await recovery.fail(recovery.begin(command), { businessCode: 400 })
    expect(recovery.pending.value).toBeNull()
    expect(recovery.needsConfirmation.value).toBe(false)
    expect(api.transitionRecovery).not.toHaveBeenCalled()
    await recovery.fail(recovery.begin(command), new Error('timeout'))
    expect(recovery.pending.value).toEqual(command)
    expect(recovery.needsConfirmation.value).toBe(true)
  })
  it.each([false, true])('只有服务端证实原请求已生效或旧修订失效才释放：%s', async superseded => {
    await recovery.fail(recovery.begin(command), new Error('timeout'))
    const applied = superseded ? null : ({ task: { id: 'task' }, nodes: [] } as unknown as TaskDetail)
    vi.mocked(api.transitionRecovery).mockResolvedValue({ applied, superseded })
    expect(await recovery.fail(recovery.begin(command), { businessCode: 409 })).toEqual({ applied, superseded })
    expect(api.transitionRecovery).toHaveBeenCalledExactlyOnceWith(command)
    expect(recovery.pending.value).toBeNull()
    expect(recovery.needsConfirmation.value).toBe(false)
  })
  it('相同修订无回执时继续保留原命令，不自动重建', async () => {
    await recovery.fail(recovery.begin(command), new Error('timeout'))
    vi.mocked(api.transitionRecovery).mockResolvedValue({ applied: null, superseded: false })
    await recovery.fail(recovery.begin(command), { businessCode: 409 })
    expect(recovery.pending.value).toEqual(command)
  })
  it('任务和账号切换不会串用原备注或请求，切回原账号才能恢复', () => {
    recovery.begin(command)
    taskId.value = 'another'
    expect(recovery.pending.value).toBeNull()
    taskId.value = 'task'
    expect(recovery.pending.value).toEqual(command)
    const user = state.user as { userInfo: { id: string } }
    user.userInfo.id = 'second'
    expect(recovery.pending.value).toBeNull()
    user.userInfo.id = 'first'
    expect(recovery.pending.value).toEqual(command)
  })
  it('卸载后仅凭会话内容恢复，迟到成功不能清除后续请求', () => {
    const first = recovery.begin(command)
    app.unmount()
    host.remove()
    mount()
    expect(recovery.pending.value).toEqual(command)
    recovery.begin({ ...command, requestKey: 'next' })
    recovery.succeed(first)
    expect(recovery.pending.value?.requestKey).toBe('next')
    app.unmount()
    host.remove()
    mount()
    expect(recovery.pending.value?.requestKey).toBe('next')
  })
  it('存储失败仍保留原请求，并启用路由和刷新离开保护', () => {
    recovery.begin({ ...command, requestKey: 'older-stored-key' })
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    const attempt = recovery.begin(command)
    expect((state.guard as () => boolean)()).toBe(true)
    app.unmount()
    host.remove()
    mount()
    expect(recovery.pending.value).toEqual(command)
    storage.mockRestore()
    recovery.succeed(attempt)
    expect((state.guard as () => boolean)()).toBe(false)
  })
  it('读取回执失败不能释放待确认命令', async () => {
    const attempt = recovery.begin(command)
    await recovery.fail(attempt, new Error('timeout'))
    vi.mocked(api.transitionRecovery).mockRejectedValue(new Error('denied'))
    await recovery.fail(recovery.begin(command), { businessCode: 400 })
    expect(recovery.pending.value).toEqual(command)
  })
})
