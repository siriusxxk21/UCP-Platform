import * as NC from '@/types/nocode/enums'
import { editDraft, newDraft } from './object-draft'
import type { FieldOptions, ObjectDesign, SaveDesign, TableBinding } from '@/types/nocode/data-center'

export function defaultFieldOptions(): FieldOptions {
  return {
    classification: NC.DataClassification.NORMAL,
    state: NC.MemberState.ACTIVE,
    options: [],
    resolver: NC.DisplayResolver.NONE,
    nativeType: null,
    primaryKey: false,
    generated: false
  }
}

export function generatedBinding(schemaName = 'public', detail = false): TableBinding {
  return {
    source: NC.ObjectSource.GENERATED,
    schemaName,
    keyColumn: 'id',
    parentColumn: detail ? 'parent_id' : null,
    structureMode: NC.StructureMode.MANAGED,
    readOnly: false,
    repairBaseFields: false,
    fingerprint: null
  }
}

export function newDesign(): SaveDesign {
  return {
    draft: newDraft(),
    restoredFieldIds: [],
    settings: { icon: null, ownerId: null, organizationId: null, titleTemplate: null },
    fieldOptions: {},
    relations: [],
    indexes: [],
    details: [],
    mainBinding: generatedBinding()
  }
}

/** 嵌套配置采用独立副本，取消编辑不污染已保存快照。 */
export function editDesign(value: ObjectDesign): SaveDesign {
  return JSON.parse(
    JSON.stringify({
      draft: { ...editDraft(value.draft), titleTemplate: value.settings.titleTemplate },
      restoredFieldIds: [],
      settings: value.settings,
      fieldOptions: value.fieldOptions,
      relations: value.relations,
      indexes: value.indexes,
      details: value.details.map(d => ({ ...d, binding: d.binding ?? generatedBinding(value.schemaName, true) })),
      mainBinding:
        value.mainBinding ??
        (value.source === NC.ObjectSource.GENERATED
          ? generatedBinding(value.schemaName)
          : {
              ...generatedBinding(value.schemaName),
              source: NC.ObjectSource.ADOPTED,
              structureMode: NC.StructureMode.RETAIN,
              readOnly: value.readOnly,
              keyColumn: Object.values(value.fieldOptions).find(o => o.primaryKey)?.columnName ?? 'id'
            })
    })
  )
}

export const stateLabels: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '已启用',
  DISABLED: '已停用',
  PUBLISHED: '已发布',
  GENERATED: '平台创建',
  ADOPTED: '已有表纳管',
  PENDING: '待发布',
  UNMANAGED: '未纳管',
  MATCHED: '结构一致',
  DRIFTED: '结构有差异',
  MAIN: '主表',
  DETAIL: '内部明细',
  RELATION: '关联表',
  SUCCEEDED: '执行成功',
  FAILED: '执行失败',
  BLOCKED: '检查未通过',
  REFERENCE: '普通引用',
  MASTER_DETAIL: '独立对象主从',
  ONE_TO_ONE: '一对一',
  MANY_TO_MANY: '多对多'
}
export function label(value: string): string {
  return stateLabels[value] ?? value
}
export function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : '操作失败，请重试'
}
export function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}
