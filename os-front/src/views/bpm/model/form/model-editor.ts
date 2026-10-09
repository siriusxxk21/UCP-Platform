/** 模型编辑只整理界面字段；未改动的可空设置按加载值提交，避免默认控件重置旧配置。 */
export const ModelType = { BPMN: 10, SIMPLE: 20 } as const
export const StartScope = { ALL: 0, USER: 1, DEPARTMENT: 2, BOTH: 3 } as const
type ModelData = Record<string, any>
export const cloneModel = <T>(value: T): T => (value === undefined ? value : JSON.parse(JSON.stringify(value)))

export function parseSimpleModel(value: unknown) {
  if (value == null || value === '') return undefined
  const parsed = typeof value === 'string' ? JSON.parse(value) : value
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
    throw new Error('SIMPLE 流程数据无效，已阻止覆盖保存')
  return cloneModel(parsed)
}

export function hydrateModel(defaults: ModelData, source: ModelData): ModelData {
  const result = { ...cloneModel(defaults), ...cloneModel(source) }
  for (const key of ['processIdRule', 'titleSetting', 'summarySetting']) {
    result[key] = { ...defaults[key], ...source[key] }
  }
  for (const key of ['managerUserIds', 'startUserIds', 'startDeptIds']) result[key] = (result[key] || []).map(String)
  result.formId = result.formId == null ? undefined : String(result.formId)
  result.simpleModel = parseSimpleModel(result.simpleModel)
  result.startUserType = result.startUserIds.length
    ? result.startDeptIds.length
      ? StartScope.BOTH
      : StartScope.USER
    : result.startDeptIds.length
      ? StartScope.DEPARTMENT
      : StartScope.ALL
  return result
}

export function modelPayload(form: ModelData, graph: unknown, source: ModelData, initial: ModelData): ModelData {
  const result = cloneModel(form)
  delete result.startUserType
  delete result.processData
  // 控件需要默认对象，但旧记录中的 null／缺省必须能原样往返。
  for (const key of ['processIdRule', 'titleSetting', 'summarySetting']) {
    if (JSON.stringify(form[key]) === JSON.stringify(initial[key])) {
      if (Object.hasOwn(source, key)) result[key] = cloneModel(source[key])
      else delete result[key]
    }
  }
  result.startUserIds = [StartScope.USER, StartScope.BOTH].includes(form.startUserType) ? [...form.startUserIds] : []
  result.startDeptIds = [StartScope.DEPARTMENT, StartScope.BOTH].includes(form.startUserType)
    ? [...form.startDeptIds]
    : []
  result.bpmnXml = form.type === ModelType.BPMN ? graph : undefined
  result.simpleModel = form.type === ModelType.SIMPLE ? graph : undefined
  if (form.formType === 0) {
    result.formId = null
    result.formCustomCreatePath = ''
    result.formCustomViewPath = ''
  }
  return result
}

/** 状态快照包含两类设计内容和所有加载的元信息，不仅比较当前步骤。 */
export function editorFingerprint(form: ModelData, graph: unknown): string {
  return JSON.stringify([form, graph])
}
