import type { InjectionKey, VNode } from 'vue'

/** 明细节点只负责位置；记录与权限仍由所属整单编辑器管理。 */
export interface FormDetailContext {
  detailId: string
  mode?: 'GRID' | 'CARDS'
  title?: string
}
export interface FormDetailRenderer {
  visible: (detailId: string) => boolean
  render: (context: FormDetailContext) => VNode[] | undefined
}
export const formDetailKey: InjectionKey<FormDetailRenderer> = Symbol.for('ucp-platform.nocode.form-detail')
