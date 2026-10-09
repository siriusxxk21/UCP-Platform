import type { PlatformNavigationSettings } from './platform-navigation'
import type { ReportPivotOptions } from './report'
import type { DatasetQuery, ReportResourceStatus } from './report-center'
export type DashboardDisplay = 'METRIC' | 'BAR' | 'LINE' | 'PIE' | 'TABLE' | 'PIVOT'
export interface DashboardChart {
  id: string
  title: string
  display: DashboardDisplay
  dataset: { id: string; versionNo: number; checksum: string }
  dimensions: DatasetQuery['dimensions']
  columnDimensions?: DatasetQuery['dimensions'] | null
  pivot?: ReportPivotOptions | null
  drillDimensions?: DatasetQuery['dimensions'] | null
  links?: DashboardLink[] | null
  metricIds: string[]
  x: number
  y: number
  w: number
  h: number
}
export interface DashboardContent {
  schemaVersion: 1
  name: string
  description: string
  charts: DashboardChart[]
  filters?: DashboardFilter[] | null
  navigation?: PlatformNavigationSettings | null
}
export interface DashboardLink {
  targetChartId: string
  sourceFieldId: string
  targetFieldId: string
}
export type DashboardFilterKind = 'TEXT' | 'SELECT' | 'MULTISELECT' | 'NUMBER_RANGE' | 'DATE_RANGE'
export interface DashboardFilter {
  id: string
  name: string
  kind: DashboardFilterKind
  mappings: { chartId: string; fieldId: string }[]
  defaultValue?: DashboardInputValue | null
}
export interface DashboardInputValue {
  values?: (string | null)[] | null
  from?: string | null
  to?: string | null
}
export interface DashboardFilterValue {
  filterId: string
  values?: (string | null)[] | null
  from?: string | null
  to?: string | null
}
export interface DashboardLinkSelection {
  chartId: string
  group: (string | null)[]
}
export interface DashboardDetail {
  id: string
  ownerId: string
  revision: number
  publishedVersion: number | null
  checksum: string
  modified: boolean
  draft: DashboardContent
  status?: ReportResourceStatus
  folderId?: string | null
  capabilities?: DashboardCapabilities | null
}
export type DashboardResourceAction = 'VIEW' | 'EXPORT' | 'EDIT' | 'PUBLISH' | 'GRANT' | 'DELETE'
export interface DashboardResourcePolicy {
  revision: number
  members: { principalKind: 'USER' | 'ROLE'; principalId: string; actions: DashboardResourceAction[] }[]
}
export interface DashboardCapabilities {
  canView: boolean
  canEdit: boolean
  canPublish: boolean
  canGrant: boolean
  canExport: boolean
  canDelete?: boolean
}
export interface DashboardAvailableItem {
  id: string
  name: string
  ownerId: string
  publishedVersion: number | null
  chartCount: number
  modified: boolean
  revision: number | null
  status?: ReportResourceStatus
  folderId?: string | null
  capabilities: DashboardCapabilities
}
export interface DashboardDeletePreview {
  id: string
  revision: number
  canDelete: boolean
  referenceCount: number
}
export interface DashboardRelease {
  id: string
  versionNo: number
  checksum: string
  content: DashboardContent
}
export interface DashboardQuery {
  id: string
  chartId: string
  preview: boolean
  versionNo?: number
  checksum?: string
  filterValues?: DashboardFilterValue[] | null
  selections?: DashboardLinkSelection[] | null
  drillPath?: (string | null)[] | null
}
export interface DashboardOptions {
  query: DashboardQuery
  filterId: string
  pageNo: number
  pageSize: number
  search?: string
}

export interface DashboardSelection {
  group: (string | null)[]
  columnGroup: (string | null)[]
  metricId?: string
}
export interface DashboardDetails extends DashboardSelection {
  query: DashboardQuery
  pageNo: number
  pageSize: number
}
export interface DashboardDetailPage {
  columns: { id: string; name: string }[]
  list: { id: string; values: (string | null)[]; labels: string[] }[]
  total: number
  pageNo: number
  pageSize: number
}

export interface DashboardPreferenceState {
  favorite: boolean
  lastVisitedAt: string | null
}
export interface DashboardPreferenceItem extends DashboardPreferenceState {
  dashboard: DashboardAvailableItem
}
