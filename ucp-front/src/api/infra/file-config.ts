import request from '@/utils/request'

export const FILE_STORAGE = {
  DB: 1,
  LOCAL: 10,
  FTP: 11,
  SFTP: 12,
  S3: 20,
} as const

export type FileStorage = (typeof FILE_STORAGE)[keyof typeof FILE_STORAGE]

export const FILE_STORAGE_OPTIONS: Array<{ label: string, value: FileStorage }> = [
  { label: '数据库', value: FILE_STORAGE.DB },
  { label: '本地存储', value: FILE_STORAGE.LOCAL },
  { label: 'FTP', value: FILE_STORAGE.FTP },
  { label: 'SFTP', value: FILE_STORAGE.SFTP },
  { label: 'S3 对象存储', value: FILE_STORAGE.S3 },
]

export interface FileClientConfig {
  domain?: string
  basePath?: string
  host?: string
  port?: number
  username?: string
  password?: string
  mode?: 'Active' | 'Passive'
  endpoint?: string
  bucket?: string
  accessKey?: string
  accessSecret?: string
  enablePathStyleAccess?: boolean
  enablePublicAccess?: boolean
  region?: string
}

export interface InfraFileConfig {
  id?: number
  name: string
  storage?: FileStorage
  master: boolean
  config: FileClientConfig
  remark?: string
  createTime?: string
}

export interface FileConfigPageResult {
  list: InfraFileConfig[]
  total: number
}

export interface FileConfigQuery {
  pageNum: number
  pageSize: number
  name?: string
  storage?: FileStorage
  createTime?: [string, string]
}

export type FileConfigSaveParams = Pick<InfraFileConfig, 'config' | 'name' | 'remark' | 'storage'> & { id?: number }

export function getFileConfigPage(params: FileConfigQuery) {
  const { pageNum, ...rest } = params
  return request.get<FileConfigPageResult>('/infra/file-config/page', {
    params: { ...rest, pageNo: pageNum },
    paramsSerializer: { indexes: null },
  })
}

export function getFileConfig(id: number) {
  return request.get<InfraFileConfig>('/infra/file-config/get', { params: { id } })
}

export function createFileConfig(data: FileConfigSaveParams) {
  return request.post<number>('/infra/file-config/create', data)
}

export function updateFileConfig(data: FileConfigSaveParams) {
  return request.put<boolean>('/infra/file-config/update', data)
}

export function updateFileConfigMaster(id: number) {
  return request.put<boolean>('/infra/file-config/update-master', undefined, { params: { id } })
}

export function deleteFileConfig(id: number) {
  return request.delete<boolean>('/infra/file-config/delete', { params: { id } })
}

export function deleteFileConfigList(ids: Array<string | number>) {
  return request.delete<boolean>('/infra/file-config/delete-list', {
    params: { ids: ids.join(',') },
  })
}

export function testFileConfig(id: number) {
  return request.get<string>('/infra/file-config/test', { params: { id } })
}
