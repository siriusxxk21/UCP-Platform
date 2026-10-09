// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { createRuntimeApi } from '@/api/nocode/runtime'
import { createBusinessFileApi } from '@/api/nocode/business-file'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import { taskFormFiles, taskFormRuntime } from './task-form-access'

function setup() {
  const post = vi.fn().mockResolvedValue({ results: [] }),
    get = vi.fn()
  const client = { post, get } as unknown as NocodeHttpClient
  const api = createTaskCenterApi(client)
  const target = { taskId: 'task', entryKey: 'wifi', recordId: 'r1', contributionId: null }
  return { client, api, target, post, get }
}
describe('任务表单授权适配', () => {
  it('默认拒绝应用浏览、普通记录读写与历史查询，不发送请求', async () => {
    const { client, post, get } = setup()
    const runtime = taskFormRuntime(createRuntimeApi(client), {})
    await expect(runtime.application('app')).rejects.toThrow('未授权')
    await expect(runtime.get('app', 'object', 'record')).rejects.toThrow('未授权')
    await expect(runtime.model('app', 'object')).rejects.toThrow('未授权')
    await expect(
      runtime.page({ applicationId: 'app', objectId: 'object', pageNo: 1, pageSize: 10, descending: true })
    ).rejects.toThrow('未授权')
    expect(post).not.toHaveBeenCalled()
    expect(get).not.toHaveBeenCalled()
  })
  it('字段规则与关联字段规则带任务及主表单上下文，不回落普通运行端点', async () => {
    const { client, api, target, post, get } = setup()
    const query = { applicationId: 'app', objectId: 'object', formId: 'form', values: {} }
    const context = { applicationId: 'app', objectId: 'object', formId: 'form', bindingId: 'related' }
    const runtime = taskFormRuntime(createRuntimeApi(client), {
      evaluateFieldRules: value => api.entryFieldRules(target, value)
    })
    await runtime.evaluateFieldRules(query)
    await api.entryRelatedFieldRules(target, context, { ...query, objectId: 'child', formId: 'child-form' })
    await api.formFieldRules('old', query)
    await api.relatedFieldRules('old', context, query)
    expect(post.mock.calls.map(call => call[0])).toEqual([
      '/nocode/tasks/entries/field-rules',
      '/nocode/tasks/entries/related-field-rules',
      '/nocode/tasks/form-runtime/field-rules',
      '/nocode/tasks/form-runtime/related-field-rules'
    ])
    expect(post.mock.calls[1]?.[1]).toEqual({
      target,
      query: { context, query: { ...query, objectId: 'child', formId: 'child-form' } }
    })
    expect(get).not.toHaveBeenCalled()
  })
  it('附件只开放字段上传、查看和会话，不授予网盘或跨对象权限', async () => {
    const { client, api, target, post, get } = setup()
    const files = taskFormFiles(
      createBusinessFileApi(client),
      api,
      () => target,
      () => ({ applicationId: 'app', objectId: 'object' })
    )
    const query = {
      applicationId: 'app',
      objectId: 'object',
      recordId: 'r1',
      fieldId: 'photo',
      pageNo: 1,
      pageSize: 10
    }
    await files.files(query)
    expect(post).toHaveBeenLastCalledWith('/nocode/tasks/entry-files/files', { target, query }, { quiet: true })
    const content = { applicationId: 'app', objectId: 'object', recordId: 'r1', fieldId: 'photo', entryId: 'file' }
    await files.content(content)
    await files.temporaryContent({
      objectId: 'object',
      detailId: 'detail',
      fieldId: 'photo',
      sessionKey: 'session',
      fileId: 'file'
    })
    await files.renew('session')
    const upload = {
      applicationId: 'app',
      objectId: 'object',
      recordId: 'r1',
      detailId: 'detail',
      fieldId: 'photo',
      sessionKey: 'session'
    }
    await files.upload(upload, new File(['test'], 'test.txt'))
    const body = post.mock.lastCall![1] as FormData
    expect(body.get('taskId')).toBe('task')
    expect(body.get('entryKey')).toBe('wifi')
    expect(body.get('detailId')).toBe('detail')
    expect(body.get('recordId')).toBe('r1')
    expect(() => files.files({ ...query, objectId: 'secret' })).toThrow('未授权')
    expect(() => files.content({ ...content, applicationId: 'secret' })).toThrow('未授权')
    await expect(files.spaces('app')).rejects.toThrow('未授权')
    await expect(files.locate(content)).rejects.toThrow('未授权')
    expect(get).not.toHaveBeenCalled()
    expect(post.mock.calls.every(([url]) => url.startsWith('/nocode/tasks/entry-files/'))).toBe(true)
  })
})
