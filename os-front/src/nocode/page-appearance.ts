import type { CSSProperties } from 'vue'
import {
  NodeKind,
  PageAlign,
  PageDirection,
  type UiNode,
  type PageStyle,
  type PageDisplay,
  type PageAction
} from '../types/nocode/application-ui'

export const styleFields = [
  'padding',
  'gap',
  'marginBottom',
  'minHeight',
  'radius',
  'background',
  'color',
  'border',
  'align',
  'direction'
] as const
export const displayFields = [
  'headingLevel',
  'alertType',
  'buttonType',
  'imageFileId',
  'imageAlt',
  'imageFit',
  'imageHeight'
] as const
export const actionFields = ['kind', 'targetNodeId', 'resourceId', 'openMode', 'confirmText'] as const
/** 编辑器属性保持标量，平台只持久化公开契约，忽略引擎内部编辑信息。 */
export function appearanceProps(node: UiNode): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const [prefix, fields, value] of [
    ['style', styleFields, node.style],
    ['display', displayFields, node.display],
    ['action', actionFields, node.action]
  ] as const)
    for (const key of fields)
      if (value && (value as Record<string, unknown>)[key] != null)
        result[`${prefix}_${key}`] = (value as Record<string, unknown>)[key]
  return result
}
export function appearanceFromProps(p: Record<string, unknown>) {
  const read = (prefix: string, fields: readonly string[]) => {
    const entries = fields
      .filter(k => p[`${prefix}_${k}`] != null && p[`${prefix}_${k}`] !== '')
      .map(k => [k, p[`${prefix}_${k}`]])
    return entries.length ? Object.fromEntries(entries) : null
  }
  return {
    style: read('style', styleFields) as PageStyle | null,
    display: read('display', displayFields) as PageDisplay | null,
    action: read('action', actionFields) as PageAction | null
  }
}
const pixels = (value: unknown, max: number) =>
  typeof value === 'number' && Number.isFinite(value) ? `${Math.max(0, Math.min(max, value))}px` : undefined
const color = (value: unknown) => (typeof value === 'string' && /^#[\da-f]{6}$/i.test(value) ? value : undefined)
/** 运行页与画布共用有限样式映射，不能把用户配置直接作为 CSS 文本注入。 */
export function pageNodeStyle(node: Pick<UiNode, 'type' | 'style'>): CSSProperties {
  const s = node.style || {}
  const result: CSSProperties = {
    padding: pixels(s.padding, 48),
    marginBottom: pixels(s.marginBottom, 48),
    minHeight: pixels(s.minHeight, 800),
    borderRadius: pixels(s.radius, 24),
    backgroundColor: color(s.background),
    color: color(s.color),
    border: s.border ? '1px solid #e5e7eb' : undefined
  }
  if (s.align && s.align !== PageAlign.SPACE_BETWEEN)
    result.textAlign = ({ START: 'left', CENTER: 'center', END: 'right' } as const)[s.align]
  if (node.type === NodeKind.FLEX) {
    result.display = 'flex'
    result.flexWrap = 'wrap'
    result.gap = pixels(s.gap ?? 12, 48)
    result.flexDirection = s.direction === PageDirection.COLUMN ? 'column' : 'row'
    result.alignItems = 'center'
    result.justifyContent = (
      { START: 'flex-start', CENTER: 'center', END: 'flex-end', SPACE_BETWEEN: 'space-between' } as const
    )[s.align || PageAlign.START]
  }
  return result
}
