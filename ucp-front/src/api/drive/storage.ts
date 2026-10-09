import request from '@/utils/request'

export interface DriveStorageOption {
  id: number
  name: string
  storage: number
  master: boolean
}

export interface DriveStorageSetting {
  selectedConfigId: number | null
  effectiveConfigId: number | null
  options: DriveStorageOption[]
}

export function getDriveStorageSetting() {
  return request.get<DriveStorageSetting>('/drive/storage/get')
}

export function updateDriveStorageSetting(configId: number | null) {
  return request.put<boolean>('/drive/storage/update', { configId })
}
