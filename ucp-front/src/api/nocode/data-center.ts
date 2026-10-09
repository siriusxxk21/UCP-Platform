import type { NocodeHttpClient } from './object'
import type * as DC from '@/types/nocode/data-center'
import { DEFAULT_PAGE_SIZE } from '@/constants'

/** 沿用底座认证客户端；不自行拼接 token、实例或另一个数据源。 */
export function createDataCenterApi(client: NocodeHttpClient) {
  return {
    formulaPreview: (body: DC.FormulaPreviewRequest) =>
      client.post<DC.FormulaPreviewResult>('/nocode/design/formula-preview', body),
    selectionChanges: (id: string) =>
      client.get<
        Array<{
          fieldId: string
          fieldName: string
          existingValues: string[]
          mapping: Record<string, string[]>
          conflicts: number
          error: string | null
        }>
      >('/nocode/design/selection-changes', { params: { id } }),
    selectionOptions: (id: string, fieldId: string) =>
      client.get<import('@/types/nocode/selection').SelectionOption[]>('/nocode/design/selection-options', {
        params: { id, fieldId }
      }),
    template: () => client.get<Blob>('/nocode/import/template', { responseType: 'blob' }),
    reconcilePreview: (id: string) =>
      client.get<DC.ReconcilePreview>('/nocode/design/reconcile-preview', { params: { id } }),
    reconcile: (body: DC.Revision & { fingerprint: string }) =>
      client.post<DC.ObjectDesign>('/nocode/design/reconcile', body),
    categories: () => client.get<string[]>('/nocode/design/categories'),
    objects: (params: DC.ObjectQuery) => client.get<DC.Page<DC.ObjectRow>>('/nocode/design/page', { params }),
    design: (id: string) => client.get<DC.ObjectDesign>('/nocode/design/get', { params: { id } }),
    inactiveFields: (id: string, detailId?: string) =>
      client.get<DC.InactiveField[]>('/nocode/design/inactive-fields', { params: { id, detailId } }),
    save: (body: DC.SaveDesign) => client.post<DC.ObjectDesign>('/nocode/design/save', body),
    edit: (body: DC.Revision) => client.post<DC.ObjectDesign>('/nocode/design/edit', body),
    copy: (body: { id: string; objectCode: string; objectName: string; tableName: string }) =>
      client.post<DC.ObjectDesign>('/nocode/design/copy', body),
    lifecycle: (action: string, body: DC.Revision) => client.post<DC.ObjectDesign>(`/nocode/design/${action}`, body),
    operationPreview: (body: DC.ObjectOperationPreviewRequest) =>
      client.post<DC.ObjectOperationPreview>('/nocode/design/operation-preview', body),
    version: (id: string, versionNo: number) =>
      client.get<Record<string, unknown>>('/nocode/design/version', { params: { id, versionNo } }),
    plan: (body: DC.Revision) => client.post<DC.PublishPlan>('/nocode/design/plan', body),
    fieldSwitchPreview: (body: DC.FieldSwitchPreviewRequest) =>
      client.post<DC.FieldSwitchPreview>('/nocode/design/field-switch-preview', body),
    fieldSwitchPreviewRows: (body: DC.FieldSwitchPreviewRowsRequest) =>
      client.post<DC.FieldConversionRows>('/nocode/design/field-switch-preview-rows', body),
    conversionRows: (planId: string, fieldId: string, pageNo: number, pageSize = 20) =>
      client.get<DC.FieldConversionRows>('/nocode/design/conversion-rows', {
        params: { planId, fieldId, pageNo, pageSize }
      }),
    execute: (planId: string, reason: string, clearFieldIds: string[] = [], suspendApplicationIds: string[] = []) =>
      client.post<DC.PublishExecution>('/nocode/design/execute', {
        planId,
        reason,
        clearFieldIds,
        suspendApplicationIds
      }),
    /** 本次对象发布里各应用的跟随结果；只用于发布后的提示，读取失败不弹全局错误。 */
    followResult: (planId: string) =>
      client.get<import('@/types/nocode/application').FollowResult[]>('/nocode/design/follow-result', {
        params: { planId },
        quiet: true
      }),
    history: (id: string) => client.get<DC.PublishExecution[]>('/nocode/design/history', { params: { id } }),
    verify: (id: string) => client.post<DC.StructureCheck[]>('/nocode/design/verify', { id }),
    schemas: () => client.get<string[]>('/nocode/table/schemas'),
    tables: (params: DC.TableQuery) => client.get<DC.Page<DC.TableRow>>('/nocode/table/page', { params }),
    table: (schema: string, name: string) =>
      client.get<DC.TableDetail>('/nocode/table/get', { params: { schema, name } }),
    preflight: (schema: string, name: string) =>
      client.get<DC.AdoptionPreflight>('/nocode/table/preflight', { params: { schema, name } }),
    adopt: (body: {
      schemaName: string
      tableName: string
      objectCode: string
      objectName: string
      titleColumn: string
      fingerprint: string
    }) => client.post<DC.ObjectDesign>('/nocode/table/adopt', body),
    preview: (schema: string, name: string, pageNo: number, pageSize = DEFAULT_PAGE_SIZE) =>
      client.get<DC.TablePreview>('/nocode/table/preview', { params: { schema, name, pageNo, pageSize } }),
    importPreview: (file: File) => {
      const body = new FormData()
      body.append('file', file)
      // 沿用底座文件/用户导入的配置，覆盖客户端默认 JSON 编码；边界由浏览器生成。
      return client.post<DC.ImportPreview>('/nocode/import/preview', body, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
    },
    importDesign: (body: {
      category?: string
      objectCode: string
      objectName: string
      tableName: string
      titleColumn: string
      columns: DC.ImportColumn[]
    }) => client.post<DC.ObjectDesign>('/nocode/import/create', body)
  }
}
export type DataCenterApi = ReturnType<typeof createDataCenterApi>
