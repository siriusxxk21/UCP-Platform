import { v4 as uuidv4 } from 'uuid'
import type { Rule } from '@form-create/ant-design-vue'
import { NodeKind, type UiNode } from '@/types/nocode/application-ui'

export const unavailableFieldComponent = 'nocodeUnavailableField'

const componentTypes: Partial<Record<NodeKind, string>> = {
  ROW: 'fcRow',
  COLUMN: 'col',
  CARD: 'aCard',
  TABS: 'nocodeTabs',
  TAB: 'nocodeTab',
  DIVIDER: 'aDivider',
  TEXT: 'nocodeText',
  VIEW: 'nocodeView',
  FORM: 'nocodeForm',
  METRIC: 'nocodeMetric',
  INTERNAL_DETAIL: 'nocodeInternalDetail'
}
/** 设计器与存储协议分离，防止引擎版本/可执行配置进入应用发布快照。 */
export function nodesToRules(
  nodes: UiNode[],
  fieldRules: Rule[] = [],
  design = false,
  detailNodes?: Record<string, UiNode[] | undefined>
): Rule[] {
  return nodes.map(node => {
    if (node.type === NodeKind.FIELD) {
      const field = fieldRules.find(rule => rule.field === node.fieldId)
      if (!field) {
        const label = node.presentation?.label || node.fieldId || node.id
        if (!design) throw new Error(`页面引用的字段“${label}”已不可用`)
        // 对象换版后旧字段仍留在原位置，用户可显式移除；占位物料不会写进持久化节点。
        return {
          type: unavailableFieldComponent,
          field: node.fieldId || undefined,
          name: node.id,
          title: label,
          props: {
            fieldId: node.fieldId,
            label,
            _osUnavailablePresentation:
              node.presentation === undefined ? undefined : JSON.parse(JSON.stringify(node.presentation))
          },
          col: { span: 24, class: 'os-field-column' },
          wrap: { show: false },
          _fc_drag_tag: unavailableFieldComponent
        }
      }
      const rule = {
        ...field,
        props: { ...field.props },
        name: node.id,
        col: { span: 24, class: 'os-field-column' },
        wrap: { ...field.wrap, extra: node.presentation?.help || undefined },
        _fc_drag_tag: 'field_' + node.fieldId
      }
      const p = node.presentation
      if (design) {
        rule.props = {
          ...rule.props,
          _osLabel: p?.label || '',
          _osPlaceholder: p?.placeholder || '',
          _osHelp: p?.help || '',
          _osReadOnly: !!p?.readOnly,
          _osHideBusinessPath: p?.showBusinessPath === false,
          _osBehavior: p?.behavior ? JSON.parse(JSON.stringify(p.behavior)) : undefined,
          _osFill: p?.fill ? JSON.parse(JSON.stringify(p.fill)) : undefined,
          _osSelection: p?.selection ? JSON.parse(JSON.stringify(p.selection)) : undefined
        }
        rule.props.disabled = !!field.props?.disabled || !!p?.readOnly
        if (p?.label) rule.title = p.label
        if (p?.placeholder) rule.props.placeholder = p.placeholder
      } else if (p) {
        if (p.label) rule.title = p.label
        rule.props = {
          ...rule.props,
          ...(p.placeholder ? { placeholder: p.placeholder } : {}),
          disabled: !!rule.props?.disabled || !!p.readOnly,
          selectionPresentation: p.selection,
          ...(p.showBusinessPath === false ? { hideBusinessPath: true } : {})
        }
      }
      return rule
    }
    const type = componentTypes[node.type]
    if (!type) throw new Error('页面节点类型无效')
    const props =
      node.type === NodeKind.INTERNAL_DETAIL
        ? {
            detailId: node.detail?.detailId || '',
            mode: node.detail?.mode || 'GRID',
            title: node.text || '',
            ...(design ? { _osDetailNodes: detailNodes?.[node.detail?.detailId || ''] } : {})
          }
        : node.type === NodeKind.COLUMN
          ? { span: node.span || 12 }
          : node.type === NodeKind.TAB
            ? { tab: node.text || '页签', key: node.id, forceRender: true }
            : node.type === NodeKind.CARD
              ? { title: node.text || '', size: 'small' }
              : node.type === NodeKind.ROW
                ? { gutter: 24 }
                : { resourceId: node.resourceId, text: node.text || '' }
    return {
      type:
        design && [NodeKind.TABS, NodeKind.TAB, NodeKind.INTERNAL_DETAIL].some(t => t === node.type)
          ? type + 'Design'
          : type,
      name: node.id,
      // fcRow 自带整行占位。普通 row 会按内容收缩，让多行分栏挤到同一行。
      col: [NodeKind.ROW, NodeKind.COLUMN, NodeKind.TAB].some(t => t === node.type) ? { show: false } : { span: 24 },
      class:
        node.type === NodeKind.COLUMN
          ? 'os-form-column'
          : node.type === NodeKind.CARD
            ? 'os-form-section'
            : node.type === NodeKind.ROW
              ? 'os-form-row'
              : undefined,
      props,
      children: nodesToRules(node.children, fieldRules, design, detailNodes),
      _fc_drag_tag:
        node.type === NodeKind.INTERNAL_DETAIL
          ? 'detail_' + node.detail?.detailId
          : design && [NodeKind.TABS, NodeKind.TAB].some(t => t === node.type)
            ? type + 'Design'
            : type
    }
  })
}
export function rulesToNodes(rules: Rule[]): UiNode[] {
  return rules.map(rule => {
    const unavailable = rule.type === unavailableFieldComponent
    const fieldId = rule.field
      ? String(rule.field)
      : unavailable && rule.props?.fieldId
        ? String(rule.props.fieldId)
        : null
    const type =
      fieldId || unavailable
        ? NodeKind.FIELD
        : (Object.keys(componentTypes) as NodeKind[]).find(
            kind => componentTypes[kind] === String(rule.type).replace(/Design$/, '')
          )
    if (!type) throw new Error('设计中包含不支持的组件，请使用业务物料和布局')
    const children = (rule.children || []).filter((value): value is Rule => typeof value === 'object' && value !== null)
    return {
      id: rule.name ? String(rule.name) : uuidv4(),
      type,
      fieldId,
      resourceId: [NodeKind.VIEW, NodeKind.FORM, NodeKind.METRIC].some(kind => kind === type)
        ? String(rule.props?.resourceId || '')
        : null,
      ...(type === NodeKind.INTERNAL_DETAIL
        ? {
            detail: {
              detailId: String(rule.props?.detailId || ''),
              mode: (rule.props?.mode || 'GRID') as 'GRID' | 'CARDS'
            }
          }
        : {}),
      text:
        type === NodeKind.INTERNAL_DETAIL
          ? String(rule.props?.title || '')
          : type === NodeKind.TAB
            ? String(rule.props?.tab || '')
            : type === NodeKind.CARD
              ? String(rule.props?.title || '')
              : type === NodeKind.TEXT
                ? String(rule.props?.text || '')
                : null,
      span: type === NodeKind.COLUMN ? Number(rule.props?.span || 12) : null,
      ...(unavailable
        ? rule.props?._osUnavailablePresentation === undefined
          ? {}
          : { presentation: JSON.parse(JSON.stringify(rule.props._osUnavailablePresentation)) }
        : fieldId &&
            (rule.props?._osLabel ||
              rule.props?._osPlaceholder ||
              rule.props?._osHelp ||
              rule.props?._osReadOnly ||
              rule.props?._osHideBusinessPath ||
              rule.props?._osBehavior ||
              rule.props?._osFill ||
              rule.props?._osSelection)
          ? {
              presentation: {
                label: String(rule.props?._osLabel || ''),
                placeholder: String(rule.props?._osPlaceholder || ''),
                help: String(rule.props?._osHelp || ''),
                readOnly: !!rule.props?._osReadOnly,
                ...(rule.props?._osHideBusinessPath ? { showBusinessPath: false } : {}),
                ...(rule.props?._osBehavior ? { behavior: JSON.parse(JSON.stringify(rule.props._osBehavior)) } : {}),
                ...(rule.props?._osFill ? { fill: JSON.parse(JSON.stringify(rule.props._osFill)) } : {}),
                ...(rule.props?._osSelection ? { selection: JSON.parse(JSON.stringify(rule.props._osSelection)) } : {})
              }
            }
          : {}),
      children: rulesToNodes(children)
    }
  })
}
export function boundFields(nodes: UiNode[]): string[] {
  return nodes.flatMap(n => (n.type === NodeKind.FIELD ? [n.fieldId!] : boundFields(n.children)))
}
