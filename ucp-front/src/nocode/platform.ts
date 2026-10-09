import { inject } from 'vue'
import type { InjectionKey } from 'vue'
import type { ObjectApi } from '@/api/nocode/object'
import type { DataCenterApi } from '@/api/nocode/data-center'
import type { ObjectDataApi } from '@/api/nocode/object-data'
import type { ApplicationApi } from '@/api/nocode/application'
import type { RuntimeApi } from '@/api/nocode/runtime'
import type { WorkApi } from '@/api/nocode/work'
import type { BusinessFileApi } from '@/api/nocode/business-file'
import type { RecordFolderApi } from '@/api/nocode/record-folder'
import type { TaskCenterApi } from '@/api/nocode/task-center'
import type { ReportCenterApi } from '@/api/nocode/report-center'
import type { ApplicationDashboardRuntimeApi } from '@/api/nocode/application-dashboard-runtime'

export interface DirectoryNode {
  id: string
  deptName: string
  disabled?: boolean
  children?: DirectoryNode[]
}
export interface NocodePlatform {
  directory: {
    users: (keyword?: string) => Promise<{ label: string; value: string }[]>
    departments: () => Promise<DirectoryNode[]>
  }
  objects: ObjectApi
  dataCenter: DataCenterApi
  objectData: ObjectDataApi
  applications: ApplicationApi
  runtime: RuntimeApi
  work: WorkApi
  bizFiles: BusinessFileApi
  recordFolders: RecordFolderApi
  taskCenter: TaskCenterApi
  reportCenter: ReportCenterApi
  applicationDashboards: ApplicationDashboardRuntimeApi
  hasPermission: (permission: string) => boolean
}
// 开发热更新时维持同一个注入标识，避免重新格式化模块后丢失已安装的底座适配。
export const nocodePlatformKey: InjectionKey<NocodePlatform> = Symbol.for('ucp-platform.nocode.platform')
export function useNocodePlatform(): NocodePlatform {
  const platform = inject(nocodePlatformKey)
  if (!platform) throw new Error('Nocode platform has not been installed')
  return platform
}
