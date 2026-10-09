import type { NocodeHttpClient } from './object'
import type { TaskWorkFormTarget } from '@/types/nocode/task-work-entries'
import type {
  BusinessFileQuery,
  BusinessFilePage,
  BusinessFileContentQuery,
  BusinessTemporaryContentQuery,
  BusinessFileUploadQuery,
  BusinessFileUploaded
} from '@/types/nocode/business-file'

/** 附件沿用业务文件服务，只将授权入口固定为当前任务办理项。 */
export function createTaskEntryFilesApi(client: NocodeHttpClient) {
  const root = '/nocode/tasks/entry-files'
  return {
    entryFiles: (target: TaskWorkFormTarget, query: BusinessFileQuery) =>
      client.post<BusinessFilePage>(`${root}/files`, { target, query }, { quiet: true }),
    entryFileContent: (target: TaskWorkFormTarget, query: BusinessFileContentQuery) =>
      client.post<Blob>(`${root}/content`, { target, query }, { responseType: 'blob', timeout: 300000, quiet: true }),
    entryFileTemporaryContent: (target: TaskWorkFormTarget, query: BusinessTemporaryContentQuery) =>
      client.post<Blob>(
        `${root}/temporary-content`,
        { target, query },
        { responseType: 'blob', timeout: 300000, quiet: true }
      ),
    entryFileRenew: (target: TaskWorkFormTarget, sessionKey: string) =>
      client.post<boolean>(`${root}/renew`, { target, sessionKey }, { quiet: true }),
    entryFileUpload: (
      target: TaskWorkFormTarget,
      query: BusinessFileUploadQuery,
      file: File,
      options?: { onProgress?: (loaded: number, total?: number) => void }
    ) => {
      const body = new FormData()
      for (const [key, value] of Object.entries({ ...query, ...target }))
        if (value != null) body.append(key, String(value))
      body.append('file', file)
      const onProgress = options?.onProgress
      return client.post<BusinessFileUploaded>(`${root}/upload`, body, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 300000,
        quiet: true,
        onUploadProgress: onProgress ? progress => onProgress(progress.loaded, progress.total) : undefined
      })
    }
  }
}
