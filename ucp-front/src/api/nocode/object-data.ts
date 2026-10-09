import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type { Aggregate, BusinessRow } from '@/types/nocode/runtime'
import type { SelectionQuery, SelectionResult } from '@/types/nocode/selection'
import type * as D from '@/types/nocode/object-data'
import type * as O from '@/types/nocode/ordered-calculation'

/** 对象管理员维护共享数据，沿用底座认证、响应和错误处理。 */
export function createObjectDataApi(client: NocodeHttpClient) {
  return {
    calculationStatus: (objectId: string) =>
      client.get<import('@/types/nocode/ordered-calculation').OrderedCalculationState[]>(
        '/nocode/object-data/calculation/status',
        { params: { objectId } }
      ),
    calculationPreview: (body: O.OrderedCalibrationPreviewRequest) =>
      client.post<O.OrderedCalibrationPreview>('/nocode/object-data/calculation/calibrate-preview', body, {
        quiet: true,
        timeout: 300000
      }),
    calibrate: (body: O.OrderedCalibrationCommand) =>
      client.post<O.OrderedCalibrationResult>('/nocode/object-data/calculation/calibrate', body, {
        quiet: true,
        timeout: 300000
      }),
    resumeCalculation: (body: O.OrderedCalibrationCommand) =>
      client.post<O.OrderedCalibrationResult>('/nocode/object-data/calculation/resume', body, {
        quiet: true,
        timeout: 300000
      }),
    retryCalculation: (body: O.OrderedCalibrationCommand) =>
      client.post<O.OrderedCalibrationResult>('/nocode/object-data/calculation/retry', body, {
        quiet: true,
        timeout: 300000
      }),
    pauseCalculation: (body: O.OrderedCalibrationCommand) =>
      client.post<O.OrderedCalibrationResult>('/nocode/object-data/calculation/pause', body, {
        quiet: true,
        timeout: 300000
      }),
    model: (objectId: string) => client.get<D.ObjectDataModel>('/nocode/object-data/model', { params: { objectId } }),
    page: (query: D.ObjectDataQuery) => client.post<Page<BusinessRow>>('/nocode/object-data/page', query),
    get: (objectId: string, id: string) =>
      client.get<Aggregate>('/nocode/object-data/get', { params: { objectId, id } }),
    save: (body: D.ObjectDataSave) =>
      client.post<Aggregate>('/nocode/object-data/save', body, { quiet: true, timeout: 300000 }),
    selection: (query: Omit<SelectionQuery, 'applicationId' | 'formId' | 'formValues' | 'detailRecordId'>) =>
      client.post<SelectionResult>('/nocode/object-data/selection', query),
    deletePreview: (body: D.ObjectDataDelete) =>
      client.post<D.ObjectDataDeletePreview>('/nocode/object-data/delete-preview', body, { quiet: true }),
    delete: (body: D.ObjectDataDelete) =>
      client.post<void>('/nocode/object-data/delete', body, { quiet: true, timeout: 300000 }),
    clearColumnPreview: (body: D.ObjectDataClearColumn) =>
      client.post<D.ObjectDataClearColumnPreview>('/nocode/object-data/clear-column-preview', body, { quiet: true }),
    clearColumn: (body: D.ObjectDataClearColumn) =>
      client.post<D.ObjectDataClearColumnResult>('/nocode/object-data/clear-column', body, {
        quiet: true,
        timeout: 300000
      })
  }
}
export type ObjectDataApi = ReturnType<typeof createObjectDataApi>
