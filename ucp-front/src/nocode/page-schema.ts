import { v4 as uuidv4 } from 'uuid'
import { NodeKind, type UiNode } from '../types/nocode/application-ui'
import { appearanceProps, appearanceFromProps } from './page-appearance'
import { parseEngineConfig } from './engine-block'

/** TinyEngine 仅编排受控组件；发布协议不包含脚本、表达式、网络请求或远程物料。 */
const components: Partial<Record<NodeKind, string>> = {
  ROW: 'OsRow',
  COLUMN: 'OsCol',
  CARD: 'OsCard',
  TABS: 'OsTabs',
  TAB: 'OsTab',
  TEXT: 'OsText',
  HEADING: 'OsHeading',
  IMAGE: 'OsImage',
  ALERT: 'OsAlert',
  BUTTON: 'OsButton',
  FLEX: 'OsFlex',
  SPACER: 'OsSpacer',
  DIVIDER: 'OsDivider',
  VIEW: 'OsView',
  REPORT: 'OsReport',
  REPORT_DASHBOARD: 'OsReportDashboard',
  FORM: 'OsForm',
  DETAIL: 'OsDetail',
  RELATED: 'OsRelated',
  ATTACHMENTS: 'OsAttachments',
  PROCESSES: 'OsProcesses',
  TASKS: 'OsTasks',
  METRIC: 'OsMetric',
  ENGINE: 'OsEngine'
}
export interface PageSchemaNode {
  id?: string
  componentName: string
  props?: Record<string, unknown>
  children?: PageSchemaNode[]
}
export function pageSchema(nodes: UiNode[]) {
  const convert = (node: UiNode): PageSchemaNode => {
    const componentName = components[node.type]
    if (!componentName) throw new Error('页面包含不支持的节点')
    return {
      id: node.id,
      componentName,
      props: {
        text: node.text || '',
        span: node.span || 12,
        resourceId: node.resourceId || '',
        relationId: node.binding?.relationId || '',
        direction: node.binding?.direction || 'INCOMING',
        ...(node.type === NodeKind.TASKS ? { taskViewJson: JSON.stringify(node.taskView || null) } : {}),
        ...(node.type === NodeKind.ENGINE ? { engineJson: JSON.stringify(node.engine || null) } : {}),
        ...appearanceProps(node)
      },
      children: node.children.map(convert)
    }
  }
  return {
    componentName: 'Page',
    fileName: 'OsBusinessPage',
    props: {},
    state: {},
    methods: {},
    css: '',
    lifeCycles: {},
    inputs: [],
    outputs: [],
    children: nodes.map(convert)
  }
}
export function pageNodes(schema: PageSchemaNode): UiNode[] {
  const ids = new Set<string>()
  const convert = (node: PageSchemaNode, depth: number): UiNode => {
    if (depth > 8 || ids.size >= 200) throw new Error('页面最多 200 个节点、8 层嵌套')
    const type = (Object.keys(components) as NodeKind[]).find(type => components[type] === node.componentName)
    if (!type) throw new Error('请使用 OS 业务物料和容器')
    const id = node.id || uuidv4()
    if (ids.has(id)) throw new Error('页面节点标识重复')
    ids.add(id)
    const p = node.props || {}
    for (const value of Object.values(p))
      if (value != null && !['string', 'number', 'boolean'].includes(typeof value))
        throw new Error('页面属性只允许固定值，不能使用可执行表达式')
    return {
      id,
      type,
      ...appearanceFromProps(p),
      fieldId: null,
      resourceId: [
        NodeKind.VIEW,
        NodeKind.REPORT,
        NodeKind.REPORT_DASHBOARD,
        NodeKind.FORM,
        NodeKind.DETAIL,
        NodeKind.RELATED,
        NodeKind.METRIC,
        NodeKind.ATTACHMENTS,
        NodeKind.PROCESSES,
        NodeKind.TASKS
      ].some(t => t === type)
        ? type === NodeKind.TASKS && !p.resourceId
          ? null
          : String(p.resourceId || '')
        : null,
      text: String(p.text || ''),
      ...(type === NodeKind.TASKS ? { taskView: parseTaskView(p.taskViewJson) } : {}),
      ...(type === NodeKind.ENGINE ? { engine: parseEngineConfig(p.engineJson) } : {}),
      span: type === NodeKind.COLUMN ? Number(p.span || 12) : null,
      binding:
        type === NodeKind.RELATED || (type === NodeKind.REPORT && !!p.relationId)
          ? { relationId: String(p.relationId || ''), direction: p.direction === 'OUTGOING' ? 'OUTGOING' : 'INCOMING' }
          : null,
      children: (node.children || []).map(child => convert(child, depth + 1))
    }
  }
  if (schema.componentName !== 'Page') throw new Error('缺少页面根节点')
  return (schema.children || []).map(node => convert(node, 0))
}

/** 设计器只携带 JSON 文本，发布时还会校验字段、条件与模板权限。 */
export function parseTaskView(value: unknown): UiNode['taskView'] {
  if (!value) return null
  const parsed = JSON.parse(String(value)) as UiNode['taskView']
  if (parsed !== null && (typeof parsed !== 'object' || Array.isArray(parsed))) throw new Error('任务视图配置格式错误')
  return parsed
}
