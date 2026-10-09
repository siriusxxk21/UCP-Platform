import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import {
  ApplicationDashboardInputSource,
  type ApplicationDashboardCatalog,
  type ApplicationDashboardConfig,
  type ApplicationDashboardInputBinding
} from '@/types/nocode/application-dashboard'
import type { DashboardFilter } from '@/types/nocode/report-dashboard'
import { FieldType, MemberState } from '@/types/nocode/enums'

const groupingTypes = new Set<string>([
  FieldType.TEXT,
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT,
  FieldType.BOOLEAN,
  FieldType.DATE,
  FieldType.DATETIME,
  FieldType.TIME,
  FieldType.SELECT,
  FieldType.AUTO_NUMBER,
  FieldType.REFERENCE,
  FieldType.UUID,
  FieldType.ORGANIZATION,
  FieldType.DEPARTMENT,
  FieldType.USER,
  FieldType.POST,
  FieldType.USER_GROUP
])
const numericTypes = new Set<string>([FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT])
const textTypes = new Set<string>([FieldType.TEXT, FieldType.TEXTAREA, FieldType.AUTO_NUMBER, FieldType.UUID])
const recordIdTypes = new Set<string>([
  FieldType.REFERENCE,
  FieldType.UUID,
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.AUTO_NUMBER,
  FieldType.INTEGER
])
const identifier = /^[A-Za-z0-9_:-]{1,100}$/

export function defaultApplicationDashboard(): ApplicationDashboardConfig {
  return { dashboard: null, contextObjectId: null, inputBindings: [], detailViews: [] }
}

/** 页面只引用资源，不重复保存看板版本、输入绑定或上下文字段。 */
export function applicationDashboardMatchesPage(resource: ApplicationResource, contextObjectId?: string | null) {
  return (
    resource.kind === ResourceKind.REPORT_DASHBOARD &&
    (!resource.config.contextObjectId || resource.config.contextObjectId === contextObjectId)
  )
}

function datasetForChart(catalog: ApplicationDashboardCatalog, chartId: string) {
  const chart = catalog.content.charts.find(value => value.id === chartId)
  return (
    chart &&
    catalog.datasets.find(
      value =>
        value.reference.id === chart.dataset.id &&
        value.reference.versionNo === chart.dataset.versionNo &&
        value.reference.checksum === chart.dataset.checksum
    )
  )
}

export function applicationDashboardDetailViews(
  catalog: ApplicationDashboardCatalog,
  chartId: string,
  resources: ApplicationResource[]
) {
  const objectId = datasetForChart(catalog, chartId)?.source.source.root.objectId
  return objectId
    ? resources.filter(value => value.kind === ResourceKind.VIEW && value.config.objectId === objectId)
    : []
}

function inputTargetTypes(catalog: ApplicationDashboardCatalog, filter: DashboardFilter) {
  return filter.mappings.map(
    mapping =>
      datasetForChart(catalog, mapping.chartId)?.source.fields.find(field => field.id === mapping.fieldId)?.type
  )
}

/** 记录上下文只绑定标量文本/单选筛选，所有映射目标都须与来源兼容。 */
export function applicationDashboardRecordIdAvailable(catalog: ApplicationDashboardCatalog, filter: DashboardFilter) {
  return (
    ['TEXT', 'SELECT'].includes(filter.kind) &&
    inputTargetTypes(catalog, filter).every(type => !!type && recordIdTypes.has(type))
  )
}

export function applicationDashboardInputFields(
  catalog: ApplicationDashboardCatalog,
  filter: DashboardFilter,
  object: PublishedObject | undefined
) {
  if (!object || !['TEXT', 'SELECT'].includes(filter.kind)) return []
  const targets = inputTargetTypes(catalog, filter)
  return object.definition.fields.filter(
    field =>
      !!field.id &&
      groupingTypes.has(field.type) &&
      object.definition.fieldOptions[field.id || '']?.state !== MemberState.INACTIVE &&
      targets.every(
        type =>
          !!type &&
          (type === field.type ||
            (numericTypes.has(type) && numericTypes.has(field.type)) ||
            (textTypes.has(type) && textTypes.has(field.type)))
      )
  )
}

/** 校验只返回独立规范化副本；错误时不删除编辑中的失效绑定，也不补对象引用。 */
export function prepareApplicationDashboard(
  config: ApplicationDashboardConfig,
  catalog: ApplicationDashboardCatalog | undefined,
  objects: Record<string, PublishedObject>,
  resources: ApplicationResource[]
): ApplicationDashboardConfig {
  const reference = config.dashboard
  if (
    !reference ||
    !/^[1-9]\d{0,18}$/.test(reference.id) ||
    !Number.isInteger(reference.versionNo) ||
    reference.versionNo < 1 ||
    !/^[a-f0-9]{64}$/.test(reference.checksum)
  )
    throw new Error('请选择已发布看板的固定版本')
  if (
    !catalog ||
    catalog.reference.id !== reference.id ||
    catalog.reference.versionNo !== reference.versionNo ||
    catalog.reference.checksum !== reference.checksum
  )
    throw new Error('请先加载并核验看板固定版本')
  const contextObjectId = config.contextObjectId || null
  if (contextObjectId && !objects[contextObjectId]) throw new Error('看板上下文对象必须已加入应用')
  const bindings = config.inputBindings || [],
    details = config.detailViews || []
  if (bindings.length > 10 || details.length > 30) throw new Error('看板最多绑定十个输入和三十个明细视图')
  const filters = new Set<string>(),
    parameters = new Set<string>(),
    charts = new Set<string>()
  const inputBindings = bindings.map(binding => {
    const filter = catalog.content.filters?.find(value => value.id === binding.filterId)
    if (!identifier.test(binding.filterId) || filters.has(binding.filterId) || !filter)
      throw new Error('看板输入筛选已失效或重复，请重新选择')
    filters.add(binding.filterId)
    const parameter = binding.parameter || null,
      fieldId = binding.fieldId || null
    if (binding.source === ApplicationDashboardInputSource.PARAMETER) {
      if (!parameter || !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(parameter) || parameters.has(parameter) || fieldId)
        throw new Error('参数输入须声明唯一参数名，不能绑定记录字段')
      parameters.add(parameter)
    } else {
      if (!contextObjectId || parameter) throw new Error('记录输入需要上下文对象，不能声明参数名')
      if (binding.source === ApplicationDashboardInputSource.RECORD_ID) {
        if (fieldId || !applicationDashboardRecordIdAvailable(catalog, filter))
          throw new Error('当前记录编号与看板筛选不兼容')
      } else if (binding.source === ApplicationDashboardInputSource.RECORD_FIELD) {
        if (
          !fieldId ||
          !applicationDashboardInputFields(catalog, filter, objects[contextObjectId]).some(
            field => field.id === fieldId
          )
        )
          throw new Error('记录字段已不可用或与看板筛选不兼容')
      } else throw new Error('看板输入来源无效')
    }
    return {
      filterId: binding.filterId,
      source: binding.source,
      parameter,
      fieldId
    } satisfies ApplicationDashboardInputBinding
  })
  const detailViews = details.map(detail => {
    if (
      !identifier.test(detail.chartId) ||
      charts.has(detail.chartId) ||
      !identifier.test(detail.viewId) ||
      !applicationDashboardDetailViews(catalog, detail.chartId, resources).some(view => view.id === detail.viewId)
    )
      throw new Error('图表明细须选择同一根对象的当前应用业务视图，且图表不能重复')
    charts.add(detail.chartId)
    return { chartId: detail.chartId, viewId: detail.viewId }
  })
  return { dashboard: { ...reference }, contextObjectId, inputBindings, detailViews }
}
