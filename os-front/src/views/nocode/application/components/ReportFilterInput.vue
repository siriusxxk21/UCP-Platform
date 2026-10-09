<script setup lang="ts">
import type { RecordModel } from '@/types/nocode/runtime'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType } from '@/types/nocode/enums'
import ReferenceField from './ReferenceField.vue'
import SelectionField from './SelectionField.vue'
import { selectionSource } from '@/nocode/selection'
import { reportEntryOptions } from '@/nocode/report'
import { datePickerPresets } from '@/nocode/relative-date'
/** detailId：字段属于统计的粒度明细时传入，字段配置与候选都按这个明细取。 */
const props = defineProps<{ model: RecordModel; field: ObjectField; applicationId: string; detailId?: string }>()
const value = defineModel<unknown>()
const target = () => props.model.object.relations.find(r => r.fieldId === props.field.id)?.targetObjectId
const options = () =>
  reportEntryOptions(
    { field: props.field, objectId: props.model.object.objectId, detailId: props.detailId },
    { [props.model.object.objectId]: { definition: props.model.object } }
  )
</script>
<template>
  <ReferenceField
    v-if="target()"
    :model-value="value == null ? null : String(value)"
    :application-id="applicationId"
    :target-object-id="target()!"
    @update:model-value="value = $event"
  />
  <SelectionField
    v-else-if="selectionSource(field, options())"
    :model-value="value == null ? null : String(value)"
    :application-id="applicationId"
    :object-id="model.object.objectId"
    :field-id="field.id!"
    :detail-id="detailId"
    placeholder="全部"
    @update:model-value="value = $event"
  />
  <a-select
    v-else-if="field.type === FieldType.BOOLEAN"
    :value="value == null ? undefined : String(value)"
    allow-clear
    :options="[
      { value: 'true', label: '是' },
      { value: 'false', label: '否' }
    ]"
    placeholder="全部"
    @change="value = $event"
  />
  <a-date-picker
    v-else-if="field.type === FieldType.DATE"
    v-model:value="value"
    value-format="YYYY-MM-DD"
    :presets="datePickerPresets()"
  />
  <a-input
    v-else
    :value="value == null ? '' : String(value)"
    allow-clear
    placeholder="全部"
    @update:value="value = $event"
  />
</template>
