import type { PageSchemaNode } from './page-schema'

/** 只切换现有列表的关联模式，保留节点身份、视图和外观，供画布及撤销历史共用。 */
export function listModeAttributes(node: PageSchemaNode, related: boolean) {
  if (!['OsView', 'OsRelated'].includes(node.componentName)) return null
  const componentName = related ? 'OsRelated' : 'OsView'
  if (node.componentName === componentName) return null
  const props: Record<string, unknown> = { ...node.props, relationId: '', direction: 'INCOMING' }
  if (!props.text || ['数据列表', '相关列表'].includes(String(props.text)))
    props.text = related ? '相关列表' : '数据列表'
  return { componentName, props }
}
