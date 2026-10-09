import { toRaw } from 'vue'
import type { ApplicationDashboardRuntimeApi } from '@/api/nocode/application-dashboard-runtime'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import type { DashboardQuery } from '@/types/nocode/report-dashboard'
import type {
  ApplicationDashboardDrill,
  ApplicationDashboardInputValue,
  ApplicationDashboardModel,
  ApplicationDashboardQuery
} from '@/types/nocode/application-dashboard-runtime'

export interface ApplicationDashboardContext {
  applicationId: string
  resourceId: string
  recordId?: string
  parameters: Record<string, ApplicationDashboardInputValue>
}

/** 只取应用资源身份与分析交互，独立看板中的版本/预览/数据集身份不会进入应用请求。 */
export function applicationDashboardQuery(
  model: ApplicationDashboardModel,
  context: ApplicationDashboardContext,
  query: DashboardQuery
): ApplicationDashboardQuery {
  if (model.applicationId !== context.applicationId || model.resourceId !== context.resourceId)
    throw new Error('应用看板上下文已变化，请刷新')
  // 参数可能来自 Vue 响应式表单，JSON 快照同时去除代理并隔离后续人工修改。
  return JSON.parse(
    JSON.stringify({
      applicationId: context.applicationId,
      resourceId: context.resourceId,
      chartId: query.chartId,
      stamp: model.stamp,
      parameters: context.parameters,
      recordId: context.recordId,
      filterValues: query.filterValues || [],
      selections: query.selections || [],
      drillPath: query.drillPath || []
    })
  ) as ApplicationDashboardQuery
}

/** 首次取图时冻结完整应用参数；后续明细/导出沿用该快照，不跟随正在编辑的参数。 */
export function createApplicationDashboardTransport(
  api: ApplicationDashboardRuntimeApi,
  context: () => ApplicationDashboardContext,
  loaded: (model: ApplicationDashboardModel) => void,
  business?: { available: (chartId: string) => boolean; open: (drill: ApplicationDashboardDrill) => void }
): DashboardRuntimeTransport {
  let model: ApplicationDashboardModel | undefined
  let generation = 0
  const queries = new WeakMap<DashboardQuery, ApplicationDashboardQuery>()
  const snapshot = (query: DashboardQuery) => {
    const identity = toRaw(query)
    let saved = queries.get(identity)
    if (!saved) {
      if (!model) throw new Error('应用看板尚未加载')
      saved = applicationDashboardQuery(model, context(), query)
      queries.set(identity, saved)
    }
    return saved
  }
  return {
    canBusinessDetails: business?.available,
    businessDetails: business
      ? body =>
          business.open({
            query: snapshot(body.query),
            group: body.group,
            columnGroup: body.columnGroup,
            metricId: body.metricId
          })
      : undefined,
    load: async () => {
      const g = ++generation,
        current = context()
      model = undefined
      const next = await api.model(current.applicationId, current.resourceId)
      if (
        g !== generation ||
        current.applicationId !== context().applicationId ||
        current.resourceId !== context().resourceId
      )
        throw new Error('应用看板上下文已变化，请刷新')
      model = next
      loaded(next)
      return next
    },
    query: (query, signal) => api.query(snapshot(query), signal),
    options: (body, signal) => api.options({ ...body, query: snapshot(body.query) }, signal),
    details: (body, signal) => api.details({ ...body, query: snapshot(body.query) }, signal),
    export: (query, signal) => api.export(snapshot(query), signal)
  }
}

export function applicationDashboardParameterReady(value?: ApplicationDashboardInputValue) {
  return !!value && (!!value.values?.length || !!value.from || !!value.to)
}
