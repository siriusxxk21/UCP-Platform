import request from '@/utils/request'

export interface InfraFile {
  id: number
  configId: number
  path: string
  name: string
  url: string
  size: number
  type?: string
  createTime: string
}

export interface InfraFilePageResult {
  list: InfraFile[]
  total: number
}

export interface InfraFileQuery {
  pageNum: number
  pageSize: number
  path?: string
  type?: string
  createTime?: [string, string]
}

export interface UploadFileParams {
  file: File
  directory?: string
}

export function getFilePage(params: InfraFileQuery) {
  const { pageNum, ...rest } = params
  return request.get<InfraFilePageResult>('/infra/file/page', {
    params: { ...rest, pageNo: pageNum },
    paramsSerializer: { indexes: null },
  })
}

export function uploadFile(data: UploadFileParams, onUploadProgress?: (percent: number) => void) {
  const formData = new FormData()
  formData.append('file', data.file)
  if (data.directory?.trim()) {
    formData.append('directory', data.directory.trim())
  }

  return request.post<InfraFile>('/infra/file/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress(event) {
      if (!event.total)
        return
      onUploadProgress?.(Math.round((event.loaded * 100) / event.total))
    },
  })
}

export function deleteFile(id: number) {
  return request.delete<boolean>('/infra/file/delete', { params: { id } })
}

export function deleteFileList(ids: Array<string | number>) {
  return request.delete<boolean>('/infra/file/delete-list', {
    params: { ids: ids.join(',') },
  })
}
