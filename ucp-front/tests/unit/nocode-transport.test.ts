import { describe, it, expect, vi } from 'vitest'
import request from '@/utils/request'
import { createDataCenterApi } from '@/api/nocode/data-center'
import { createRuntimeApi } from '@/api/nocode/runtime'
import { createObjectDataApi } from '@/api/nocode/object-data'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { createWorkApi } from '@/api/nocode/work'
import type { InternalAxiosRequestConfig } from 'axios'

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({
    token: 'test-session',
    isSessionIdle: () => false,
    updateToken: vi.fn(),
    logout: vi.fn()
  })
}))
vi.mock('ant-design-vue', () => ({ notification: { error: vi.fn() } }))

/** 复用真实底座客户端的请求转换链，防止结构导入被默认 JSON Content-Type 转成普通对象。 */
describe('data center base transport', () => {
  it('extends only business writes that can synchronously recalculate an ordered group', async () => {
    const original = request.defaults.adapter
    const defaultTimeout = request.defaults.timeout
    const observed: InternalAxiosRequestConfig[] = []
    request.defaults.adapter = async config => {
      observed.push(config)
      return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, data: null } }
    }
    try {
      const runtime = createRuntimeApi(request),
        maintenance = createObjectDataApi(request),
        task = createTaskEntryApi(request),
        work = createWorkApi(request)
      const record = {
        applicationId: 'app',
        objectId: 'object',
        id: null,
        expectedRevision: null,
        values: {},
        requestKey: 'same-key'
      }
      const entry = { applicationId: 'app', entryId: 'entry', version: 1 }
      const deletion = { applicationId: 'app', objectId: 'object', id: 'row', expectedRevision: '1' }
      await maintenance.save({ ...record, versionNo: 1, checksum: 'v1' })
      await maintenance.delete({ ...deletion, versionNo: 1, checksum: 'v1' })
      await maintenance.clearColumn({ objectId: 'object', versionNo: 1, checksum: 'v1', fieldId: 'amount' })
      await runtime.save(record)
      await runtime.submit(record)
      await runtime.delete(deletion)
      await runtime.action({
        applicationId: 'app',
        objectId: 'object',
        actionId: 'action',
        recordId: 'row',
        expectedRevision: '1'
      })
      await runtime.import('app', 'object', new File(['name\nfixture'], 'fixture.csv', { type: 'text/csv' }))
      await task.save(entry, record)
      await task.submit(entry, record)
      await task.delete(entry, 'row', '1')
      await work.submit({ draftId: 'draft', expectedRevision: 1, idempotencyKey: 'same-key' })
      expect(observed.map(config => config.url)).toEqual([
        '/nocode/object-data/save',
        '/nocode/object-data/delete',
        '/nocode/object-data/clear-column',
        '/nocode/runtime/save',
        '/nocode/handling/submit',
        '/nocode/runtime/delete',
        '/nocode/runtime/action',
        '/nocode/runtime/import',
        '/nocode/task-entry/save',
        '/nocode/task-entry/submit',
        '/nocode/task-entry/delete',
        '/nocode/work-draft/submit'
      ])
      for (const config of observed) expect(config.timeout).toBe(300000)
      expect(observed.find(config => config.url?.endsWith('/import'))?.data).toBeInstanceOf(FormData)
      observed.length = 0
      await maintenance.model('object')
      await maintenance.clearColumnPreview({ objectId: 'object', versionNo: 1, checksum: 'v1', fieldId: 'amount' })
      await runtime.model('app', 'object')
      await runtime.page({ applicationId: 'app', objectId: 'object', pageNo: 1, pageSize: 20 })
      await runtime.submitReceipt('app', 'object', 'same-key')
      await task.context(entry)
      await task.submitReceipt(entry, 'same-key')
      await task.saveDraft(entry, record, null)
      await work.context('draft')
      for (const config of observed) expect(config.timeout).toBe(defaultTimeout)
      expect(request.defaults.timeout).toBe(defaultTimeout)
    } finally {
      request.defaults.adapter = original
    }
  })

  it.each([
    ['calculationPreview', 'calibrate-preview'],
    ['calibrate', 'calibrate'],
    ['resumeCalculation', 'resume'],
    ['retryCalculation', 'retry'],
    ['pauseCalculation', 'pause']
  ] as const)('keeps %s calibration timeout local to the long-running request', async (method, endpoint) => {
    const original = request.defaults.adapter
    const defaultTimeout = request.defaults.timeout
    const observed: InternalAxiosRequestConfig[] = []
    request.defaults.adapter = async config => {
      observed.push(config)
      return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, data: null } }
    }
    try {
      const api = createObjectDataApi(request)
      await api[method]({
        objectId: 'test-object',
        versionNo: 1,
        checksum: 'version-1',
        fieldIds: ['balance'],
        signatures: { balance: 'rule-1' },
        requestId: 'unchanged-request-id',
        maxGroups: 1
      })
      expect(observed[0]?.url).toBe('/nocode/object-data/calculation/' + endpoint)
      expect(observed[0]?.timeout).toBe(300000)
      expect(request.defaults.timeout).toBe(defaultTimeout)
      await api.model('test-object')
      expect(observed[1]?.timeout).toBe(defaultTimeout)
    } finally {
      request.defaults.adapter = original
    }
  })

  it('rejects report export permission errors instead of downloading JSON as Excel', async () => {
    const original = request.defaults.adapter
    request.defaults.adapter = async config => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: new Blob([JSON.stringify({ code: 403, msg: '没有导出权限' })], { type: 'application/json' })
    })
    try {
      await expect(createRuntimeApi(request).reportExport({ applicationId: '1', reportId: 'report' })).rejects.toThrow(
        '没有导出权限'
      )
    } finally {
      request.defaults.adapter = original
    }
  })

  it('sends the file as multipart and preserves authentication and response unwrapping', async () => {
    const original = request.defaults.adapter
    let observed: any
    request.defaults.adapter = async config => {
      observed = config
      return {
        config,
        status: 200,
        statusText: 'OK',
        headers: {},
        data: { code: 0, data: { columns: [], errors: [] } }
      }
    }
    try {
      const file = new File(['name,名称,TEXT'], 'fields.csv', { type: 'text/csv' })
      const result = await createDataCenterApi(request).importPreview(file)
      expect(observed.headers.get('Content-Type')).toContain('multipart/form-data')
      expect(observed.headers.get('Authorization')).toBe('Bearer test-session')
      expect(observed.data).toBeInstanceOf(FormData)
      expect(observed.data.get('file').name).toBe('fields.csv')
      expect(result).toEqual({ columns: [], errors: [] })
    } finally {
      request.defaults.adapter = original
    }
  })
})
