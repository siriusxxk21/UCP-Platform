<script setup lang="ts">
import { computed, inject } from 'vue'
import { internalDetailDesignerKey } from '@/nocode/internal-detail-designer-context'
import type { UiNode } from '@/types/nocode/application-ui'
import DetailFormSettings from './DetailFormSettings.vue'

const props = defineProps<{ detailId?: string }>()
const model = defineModel<UiNode[] | undefined>()
const context = inject(internalDetailDesignerKey, undefined)
const definition = computed(() => context?.definition.value)
const detail = computed(() => definition.value?.details.find(item => item.id === props.detailId))
const selectedFieldId = computed(() =>
  context?.selectedColumn.value?.detailId === props.detailId ? context?.selectedColumn.value?.fieldId : undefined
)
function setSelectedField(fieldId: string) {
  if (props.detailId) context?.setSelectedField(props.detailId, fieldId)
}
</script>
<template>
  <DetailFormSettings
    v-if="context && definition && detail"
    v-model="model"
    :definition="definition"
    :detail="detail"
    :objects="context.objects.value"
    :resources="context.resources.value"
    :form-field-ids="context.formFieldIds.value"
    :read-only="context.readOnly.value"
    :selected-field-id="selectedFieldId"
    compact
    @select-field="setSelectedField"
  />
  <p v-else>此内部明细已不可用，请同步对象版本后检查。</p>
</template>
