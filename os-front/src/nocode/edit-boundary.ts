import type { InjectionKey } from 'vue'
/** 编辑容器收集其内表单关闭检查，包括详情页中的多个业务区块。 */
export const editorCloseKey: InjectionKey<Set<() => Promise<boolean>>> = Symbol('nocode-editor-close')
