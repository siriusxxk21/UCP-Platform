<script setup lang="ts">
import { computed, markRaw, provide, ref } from 'vue'
import OsDynamicSearch from '@/components/os-table-page/OsDynamicSearch.vue'
import type { DynamicSearchCondition } from '@/components/os-table-page/types'
import { OPERATOR_LABELS } from '@/components/os-table-page/types'
import type { PublishedDefinition, ObjectReference } from '@/types/nocode/application'
import { dynamicQueryField, normalizeAdvancedQuery } from '@/nocode/runtime-list'
import { fieldRelation } from '@/nocode/business-fields'
import { selectionSource, selectionPreviewKey } from '@/nocode/selection'
import { reportEntryOptions, type reportFieldOptions } from '@/nocode/report'
import { describeRelativeDate, isRelativeDate } from '@/nocode/relative-date'
import SelectionField from './SelectionField.vue'

const props = defineProps<{
  applicationId: string
  entries: ReturnType<typeof reportFieldOptions>
  objects: Record<string, { definition: PublishedDefinition }>
  previewObjects?: ObjectReference[]
  label?: string
}>()
const value = defineModel<DynamicSearchCondition | null>()
const open = ref(false)
provide(
  selectionPreviewKey,
  computed(() => ({ applicationId: props.applicationId, objects: props.previewObjects || [] }))
)
const fields = computed(() =>
  props.entries.map(entry => {
    const definition = props.objects[entry.objectId]?.definition
    // 粒度明细的字段取明细自己的字段配置（条目带 detailId）；其余取对象的。
    const options = reportEntryOptions(entry, props.objects)
    const choices = (options?.options || []).filter(o => !o.disabled).map(o => ({ value: o.code, label: o.label }))
    const field = dynamicQueryField({ ...entry.field, id: entry.value, name: entry.label }, options, choices)
    const source = selectionSource(entry.field, options)
    if (fieldRelation(definition?.relations, entry.field.id) || (source && source.kind !== 'LOCAL_OPTIONS')) {
      field.type = 'select'
      field.operators = ['eq', 'neq', 'in']
      field.valueComponent = markRaw(SelectionField)
      field.valueProps = {
        applicationId: props.applicationId,
        objectId: entry.objectId,
        fieldId: entry.field.id,
        ...(entry.detailId ? { detailId: entry.detailId } : {}),
        preview: !!props.previewObjects,
        placeholder: '选择条件值'
      }
    }
    field.operators = [...(field.operators || []), 'isNull', 'notNull']
    return field
  })
)
const summary = computed(() => {
  if (!value.value) return '不额外限制'
  const describe = (items: DynamicSearchCondition['items'], logic: string): string =>
    items
      .map(item => {
        if (item.type === 'group') return '(' + describe(item.groupItems, item.groupLogic) + ')'
        const f = fields.value.find(f => f.field === item.field)
        const values = Array.isArray(item.value) ? item.value : [item.value]
        // 日期字段的取值控件是「具体 / 相对日期」，摘要照常写出日期或「本月」「过去 7 天」。
        const text = isRelativeDate(item.value)
          ? describeRelativeDate(item.value)
          : f?.valueComponent && f.type !== 'dateRange' && f.type !== 'datetimeRange'
            ? `已选 ${values.length} 项`
            : values.map(v => f?.options?.find(o => String(o.value) === String(v))?.label ?? String(v ?? '')).join('、')
        return `${f?.label || '字段已失效'} ${OPERATOR_LABELS[item.operator]} ${['isNull', 'notNull'].includes(item.operator) ? '' : text}`
      })
      .join(logic === 'AND' ? ' 且 ' : ' 或 ')
  return describe(value.value.items, value.value.logic)
})
function confirm(conditions: DynamicSearchCondition | null) {
  value.value = normalizeAdvancedQuery(
    conditions,
    props.entries.map(e => ({ ...e.field, id: e.value }))
  )
}
</script>
<template>
  <div class="report-condition">
    <a-button :disabled="!entries.length" @click="open = true">{{ label || '设置条件' }}</a-button>
    <span class="condition-summary" :title="summary">{{ summary }}</span>
    <a-button v-if="value" type="link" @click="value = null">清除</a-button>
    <OsDynamicSearch v-model:open="open" :model-value="value" :fields="fields" strict @confirm="confirm" />
  </div>
</template>
<style scoped>
.report-condition {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.condition-summary {
  flex: 1;
  min-width: 120px;
  color: #64748b;
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
