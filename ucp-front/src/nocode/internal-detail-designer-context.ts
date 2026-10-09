import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { ApplicationResource, PublishedDefinition, PublishedObject } from '@/types/nocode/application'

/** 全局注册的引擎控件从所属设计器注入状态，不能闭包捕获最近打开的另一个设计器。 */
export interface InternalDetailDesignerContext {
  definition: ComputedRef<PublishedDefinition | undefined>
  objects: ComputedRef<Record<string, PublishedObject>>
  resources: ComputedRef<ApplicationResource[]>
  formFieldIds: ComputedRef<string[]>
  readOnly: ComputedRef<boolean>
  selectedColumn: Ref<{ detailId: string; fieldId?: string } | undefined>
  select: (detailId: string, fieldId?: string) => void
  setSelectedField: (detailId: string, fieldId: string) => void
}

export const internalDetailDesignerKey: InjectionKey<InternalDetailDesignerContext> = Symbol.for(
  'richuang.nocode.internal-detail-designer'
)
