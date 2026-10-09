import type { NocodeHttpClient } from './object'
import type {
  BusinessFileContentQuery,
  BusinessFileConfigSpace,
  BusinessFileDirectory,
  BusinessFileDirectoryPage,
  BusinessFileDirectoryQuery,
  BusinessFileFavoriteQuery,
  BusinessFileMarkQuery,
  BusinessFilePage,
  BusinessFileQuery,
  BusinessFileSpace,
  BusinessTemporaryContentQuery,
  BusinessFileUploadQuery,
  BusinessFileUploaded
} from '@/types/nocode/business-file'

/** 业务文件请求统一复用底座客户端；入口身份、上传会话与内容授权都由服务端逐次重验。 */
export function createBusinessFileApi(client: NocodeHttpClient) {
  return {
    configSpaces: () => client.get<BusinessFileConfigSpace[]>('/nocode/biz-file/config/spaces', { quiet: true }),
    spaces: (applicationId?: string) =>
      client.post<BusinessFileSpace[]>('/nocode/biz-file/objects', { applicationId }, { quiet: true }),
    directories: (query: BusinessFileDirectoryQuery) =>
      client.post<BusinessFileDirectoryPage>('/nocode/biz-file/directories', query, { quiet: true }),
    files: (query: BusinessFileQuery) =>
      client.post<BusinessFilePage>('/nocode/biz-file/files', query, { quiet: true }),
    locate: (query: BusinessFileContentQuery) =>
      client.post<BusinessFileDirectory[]>('/nocode/biz-file/locate', query, { quiet: true }),
    markedFiles: (query: BusinessFileMarkQuery) =>
      client.post<BusinessFilePage>('/nocode/biz-file/mark/files', query, { quiet: true }),
    favorite: (query: BusinessFileFavoriteQuery) =>
      client.post<boolean>('/nocode/biz-file/mark/favorite', query, { quiet: true }),
    access: (query: BusinessFileContentQuery) =>
      client.post<boolean>('/nocode/biz-file/mark/access', query, { quiet: true }),
    content: (query: BusinessFileContentQuery) =>
      client.get<Blob>('/nocode/biz-file/content', {
        params: { ...query, inline: false },
        responseType: 'blob',
        timeout: 300000,
        quiet: true
      }),
    temporaryContent: (query: BusinessTemporaryContentQuery) =>
      client.get<Blob>('/nocode/biz-file/upload/content', {
        params: { ...query, inline: false },
        responseType: 'blob',
        timeout: 300000,
        quiet: true
      }),
    upload: (
      query: BusinessFileUploadQuery,
      file: File,
      options?: { onProgress?: (loaded: number, total?: number) => void }
    ) => {
      const body = new FormData()
      if (query.applicationId) body.append('applicationId', query.applicationId)
      body.append('objectId', query.objectId)
      if (query.recordId) body.append('recordId', query.recordId)
      if (query.detailId) body.append('detailId', query.detailId)
      body.append('fieldId', query.fieldId)
      body.append('sessionKey', query.sessionKey)
      if (query.idempotencyKey) body.append('idempotencyKey', query.idempotencyKey)
      body.append('file', file)
      const onProgress = options?.onProgress
      return client.post<BusinessFileUploaded>('/nocode/biz-file/upload', body, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 300000,
        quiet: true,
        onUploadProgress: onProgress ? progress => onProgress(progress.loaded, progress.total) : undefined
      })
    },
    renew: (sessionKey: string) =>
      client.post<boolean>(`/nocode/biz-file/upload/renew?sessionKey=${encodeURIComponent(sessionKey)}`, undefined, {
        quiet: true
      })
  }
}
export type BusinessFileApi = ReturnType<typeof createBusinessFileApi>
