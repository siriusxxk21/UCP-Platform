import type { NocodeHttpClient } from './object'
import type { DriveId } from '@/types/drive'
import type {
  RecordFolderBackfillQuery,
  RecordFolderBackfillResult,
  RecordFolderCandidate,
  RecordFolderEntry,
  RecordFolderEntryQuery,
  RecordFolderNameField,
  RecordFolderOpenQuery,
  RecordFolderOpened,
  RecordFolderSource,
  RecordFolderSourceInput
} from '@/types/nocode/record-folder'

/** 记录文件夹请求统一复用底座客户端；凭据与「能看 / 能改这条记录」由服务端逐次重验，失败由调用方自己呈现。 */
export function createRecordFolderApi(client: NocodeHttpClient) {
  return {
    config: (objectId: string) =>
      client.get<RecordFolderSource[]>('/nocode/record-folder/config', { params: { objectId }, quiet: true }),
    saveConfig: (objectId: string, sources: RecordFolderSourceInput[]) =>
      client.post<RecordFolderSource[]>('/nocode/record-folder/config/save', { objectId, sources }, { quiet: true }),
    candidates: (objectId: string) =>
      client.get<RecordFolderCandidate[]>('/nocode/record-folder/config/candidates', {
        params: { objectId },
        quiet: true
      }),
    nameFields: (objectId: string) =>
      client.get<RecordFolderNameField[]>('/nocode/record-folder/config/name-fields', {
        params: { objectId },
        quiet: true
      }),
    backfill: (query: RecordFolderBackfillQuery) =>
      client.post<RecordFolderBackfillResult>('/nocode/record-folder/config/backfill', query, { quiet: true }),
    open: (query: RecordFolderOpenQuery) =>
      client.post<RecordFolderOpened>('/nocode/record-folder/open', query, { quiet: true }),
    list: (query: RecordFolderEntryQuery) =>
      client.post<RecordFolderEntry[]>('/nocode/record-folder/entry/list', query, { quiet: true }),
    get: (query: RecordFolderEntryQuery) =>
      client.post<RecordFolderEntry>('/nocode/record-folder/entry/get', query, { quiet: true }),
    path: (query: RecordFolderEntryQuery) =>
      client.post<string[]>('/nocode/record-folder/entry/path', query, { quiet: true }),
    search: (query: RecordFolderEntryQuery) =>
      client.post<RecordFolderEntry[]>('/nocode/record-folder/entry/search', query, { quiet: true }),
    createFolder: (query: RecordFolderEntryQuery) =>
      client.post<DriveId>('/nocode/record-folder/entry/create-folder', query, { quiet: true }),
    rename: (query: RecordFolderEntryQuery) =>
      client.post<boolean>('/nocode/record-folder/entry/rename', query, { quiet: true }),
    move: (query: RecordFolderEntryQuery) =>
      client.post<boolean>('/nocode/record-folder/entry/move', query, { quiet: true }),
    copy: (query: RecordFolderEntryQuery) =>
      client.post<DriveId>('/nocode/record-folder/entry/copy', query, { quiet: true }),
    trash: (query: RecordFolderEntryQuery) =>
      client.post<boolean>('/nocode/record-folder/entry/trash', query, { quiet: true }),
    trashList: (query: RecordFolderEntryQuery) =>
      client.post<RecordFolderEntry[]>('/nocode/record-folder/entry/trash-list', query, { quiet: true }),
    restore: (query: RecordFolderEntryQuery) =>
      client.post<RecordFolderEntry>('/nocode/record-folder/entry/restore', query, { quiet: true }),
    content: (query: RecordFolderEntryQuery, inline: boolean) =>
      client.get<Blob>('/nocode/record-folder/entry/content', {
        params: { ...query, inline },
        responseType: 'blob',
        timeout: 300000,
        quiet: true
      })
  }
}
export type RecordFolderApi = ReturnType<typeof createRecordFolderApi>
