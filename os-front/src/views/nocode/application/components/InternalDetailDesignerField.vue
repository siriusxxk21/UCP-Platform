<script setup lang="ts">
import { computed, inject } from 'vue'
import { internalDetailDesignerKey } from '@/nocode/internal-detail-designer-context'
import type { UiNode } from '@/types/nocode/application-ui'
import InternalDetailDesignBlock from './InternalDetailDesignBlock.vue'

const props = defineProps<{
  detailId?: string
  title?: string
  mode?: string
  _osDetailNodes?: UiNode[]
}>()
const context = inject(internalDetailDesignerKey, undefined)
const detail = computed(() => context?.definition.value?.details.find(item => item.id === props.detailId))
const selectedFieldId = computed(() =>
  context?.selectedColumn.value?.detailId === props.detailId ? context?.selectedColumn.value?.fieldId : undefined
)
function select(fieldId?: string) {
  if (props.detailId) context?.select(props.detailId, fieldId)
}
</script>
<template>
  <InternalDetailDesignBlock
    :definition="detail"
    :title="title"
    :mode="mode"
    :nodes="props._osDetailNodes"
    :selected-field-id="selectedFieldId"
    @select="select"
  />
</template>
