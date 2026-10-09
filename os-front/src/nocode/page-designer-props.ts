import { actionFields, displayFields, styleFields } from './page-appearance'

/**
 * 宿主属性面板经 postMessage（PROPS）写进设计器画布节点的属性键；不在表里的键在画布侧一律丢弃。
 * 必须覆盖 pageSchema() 给任一节点生成的全部属性 —— 漏一个，这个属性在设计器里改了也不会进草稿
 * （R9 实测：漏了设计引擎区块的 engineJson，配好的材料库与写回保存后变成 null）。
 */
export const HOST_PROP_KEYS: readonly string[] = [
  'text',
  'span',
  'resourceId',
  'taskViewJson',
  'engineJson',
  'relationId',
  'direction',
  'imageUrl',
  ...styleFields.map(k => `style_${k}`),
  ...displayFields.map(k => `display_${k}`),
  ...actionFields.map(k => `action_${k}`)
]

/** 只保留允许由宿主写入的属性（值原样，不做转换）。 */
export function pickHostProps(props: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(HOST_PROP_KEYS.filter(key => key in props).map(key => [key, props[key]]))
}
