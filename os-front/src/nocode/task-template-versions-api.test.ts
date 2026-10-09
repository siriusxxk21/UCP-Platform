import { describe, expect, it, vi } from 'vitest'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'
import { newTaskNode } from './task-center'

describe('任务模板版本传输契约', () => {
  const fixture = () => ({
    id: 'template',
    revision: 7,
    publishedVersion: 4,
    primaryVersion: 2,
    task: newTaskNode(),
    nodes: []
  })
  it('版本目录保留所有真实版本和主版本标识，不展开为草稿配置', async () => {
    const versions = [
      { version: 2, name: '历史版本', description: '', nodeCount: 3, publishedAt: '2026-10-05', primary: true }
    ]
    const get = vi.fn().mockResolvedValue(versions)
    const api = createTaskCenterApi({ get } as unknown as NocodeHttpClient)
    expect(await api.templateVersions('template')).toEqual(versions)
    expect(get).toHaveBeenCalledWith('/nocode/task-templates/versions', { params: { id: 'template' } })
  })
  it('切主版本携带并发修订号且读取响应中的最新主版本与草稿修订', async () => {
    const post = vi.fn().mockResolvedValue(fixture())
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    expect(await api.setPrimaryTemplateVersion('template', 2, 6)).toMatchObject({ primaryVersion: 2, revision: 7 })
    expect(post).toHaveBeenCalledWith('/nocode/task-templates/primary', {
      id: 'template',
      version: 2,
      expectedRevision: 6
    })
  })
  it('默认主版本请求不固定旧目录版本，明确历史版本则原样传递', async () => {
    const get = vi.fn().mockResolvedValue({ ...fixture(), version: 2 })
    const api = createTaskCenterApi({ get } as unknown as NocodeHttpClient)
    await api.templateVersion('template')
    expect(get).toHaveBeenLastCalledWith('/nocode/task-templates/version', {
      params: { id: 'template', version: undefined }
    })
    await api.templateVersion('template', 1)
    expect(get).toHaveBeenLastCalledWith('/nocode/task-templates/version', { params: { id: 'template', version: 1 } })
  })
  it('发布可明确保留或切换主版本，旧调用不额外注入默认参数', async () => {
    const post = vi.fn().mockResolvedValue({ ...fixture(), version: 5 })
    const api = createTaskCenterApi({ post } as unknown as NocodeHttpClient)
    await api.publishTemplate('template', 7, false)
    expect(post).toHaveBeenLastCalledWith('/nocode/task-templates/publish', {
      id: 'template',
      expectedRevision: 7,
      setAsPrimary: false
    })
    await api.publishTemplate('template', 7, true)
    expect(post).toHaveBeenLastCalledWith('/nocode/task-templates/publish', {
      id: 'template',
      expectedRevision: 7,
      setAsPrimary: true
    })
    await api.publishTemplate('template', 7)
    expect(post).toHaveBeenLastCalledWith('/nocode/task-templates/publish', { id: 'template', expectedRevision: 7 })
  })
})
