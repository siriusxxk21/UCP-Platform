<script setup lang="ts">
import { provide } from 'vue'
import { nocodePlatformKey, useNocodePlatform } from '@/nocode/platform'
import SelectionField from '../application/components/SelectionField.vue'

defineProps<{
  objectId: string
  fieldId: string
  recordId?: string
  modelValue?: string | string[] | null
  multiple?: boolean
  creating?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | string[] | null] }>()
const platform = useNocodePlatform()
// 复用统一候选交互，仅将对象管理场景的候选查询交由专门的后端权限入口。
provide(nocodePlatformKey, {
  ...platform,
  runtime: {
    ...platform.runtime,
    selection: query =>
      platform.objectData.selection({
        objectId: query.objectId,
        fieldId: query.fieldId,
        recordId: query.recordId,
        detailId: query.detailId,
        creating: query.creating,
        search: query.search,
        pageNo: query.pageNo,
        pageSize: query.pageSize,
        selected: query.selected
      })
  }
})
</script>

<template>
  <SelectionField
    application-id=""
    :object-id="objectId"
    :field-id="fieldId"
    :record-id="recordId"
    :model-value="modelValue"
    :multiple="multiple"
    :creating="creating"
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>
