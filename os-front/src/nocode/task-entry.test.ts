import { describe, expect, it, vi } from 'vitest'
import { taskEntryRuntime } from './task-entry'
import { createRuntimeApi } from '@/api/nocode/runtime'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskEntryContext } from '@/types/nocode/task-entry'

describe('任务办理请求边界', () => {
  function fixture() {
    const get = vi.fn().mockResolvedValue({}),
      post = vi.fn().mockResolvedValue({})
    const client = { get, post } as unknown as NocodeHttpClient
    const context = {
      entry: { applicationId: 'app', entryId: 'entry', version: 3 },
      config: { objectId: 'object' },
      model: {}
    } as TaskEntryContext
    return { get, post, runtime: taskEntryRuntime(createRuntimeApi(client), createTaskEntryApi(client), context) }
  }
  it('保存强制走任务端点，并携带打开时的入口版本', async () => {
    const { runtime, post } = fixture()
    const record = {
      applicationId: 'app',
      objectId: 'object',
      id: null,
      expectedRevision: null,
      values: {},
      details: {}
    }
    await runtime.save(record)
    expect(post).toHaveBeenCalledWith(
      '/nocode/task-entry/save',
      { entry: { applicationId: 'app', entryId: 'entry', version: 3 }, record },
      { quiet: true, timeout: 300000 }
    )
  })
  it('未适配功能拒绝，不回退普通应用 API', async () => {
    const { runtime, post, get } = fixture()
    await expect(runtime.application('app')).rejects.toThrow('暂未开放')
    await expect(runtime.mine()).rejects.toThrow('暂未开放')
    await expect(runtime.template('app', 'object')).rejects.toThrow('暂未开放')
    expect(post).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()
  })
  it('审批提交与恢复结果都限定任务入口和打开版本', async () => {
    const { runtime, post } = fixture()
    const record = {
      applicationId: 'app',
      objectId: 'object',
      id: null,
      expectedRevision: null,
      values: {},
      details: {},
      requestKey: 'same-request'
    }
    await runtime.submit(record)
    await runtime.submitReceipt('app', 'object', 'same-request')
    expect(post.mock.calls.map(call => call[0])).toEqual([
      '/nocode/task-entry/submit',
      '/nocode/task-entry/submit-receipt'
    ])
    expect(post.mock.calls[1]![1]).toEqual({
      entry: { applicationId: 'app', entryId: 'entry', version: 3 },
      requestKey: 'same-request'
    })
    await expect(runtime.submit({ ...record, objectId: 'other' })).rejects.toThrow('不能切换')
    expect(post).toHaveBeenCalledTimes(2)
  })
  it('关联填充使用入口专用端点，跨对象不发请求', async () => {
    const { runtime, post } = fixture()
    const query = {
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      sourceFieldId: 'supplier',
      selectedId: '1'
    }
    await runtime.formFill(query)
    expect(post).toHaveBeenCalledWith(
      '/nocode/task-entry/form-fill',
      {
        entry: { applicationId: 'app', entryId: 'entry', version: 3 },
        query
      },
      { quiet: true }
    )
    await expect(runtime.formFill({ ...query, objectId: 'other' })).rejects.toThrow('不能切换')
    expect(post).toHaveBeenCalledTimes(1)
  })
  it('跨对象或跨应用请求不会发出', async () => {
    const { runtime, post, get } = fixture()
    await expect(runtime.get('other', 'object', '1')).rejects.toThrow('不能切换')
    await expect(runtime.get('app', 'other', '1')).rejects.toThrow('不能切换')
    expect(post).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()
  })
})
