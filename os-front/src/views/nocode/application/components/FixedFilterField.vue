<script setup lang="ts">
import { computed, provide } from 'vue'
import type { PublishedObject } from '@/types/nocode/application'
import type { ObjectField } from '@/types/nocode/object'
import { selectionSource, selectionPreviewKey } from '@/nocode/selection'
import { fieldRelation } from '@/nocode/business-fields'
import { reportEntryOptions } from '@/nocode/report'
import RecordQueryField from './RecordQueryField.vue'
import SelectionField from './SelectionField.vue'

const props = defineProps<{
  applicationId?: string
  entry: { field: ObjectField; objectId: string; detailId?: string }
  objects: Record<string, PublishedObject>
}>()
const value = defineModel<any>()
const definition = computed(() => props.objects[props.entry.objectId]!.definition)
// 粒度明细的字段取明细自己的字段配置（条目带 detailId）；其余取对象的。
const options = computed(() => reportEntryOptions(props.entry, props.objects))
const selection = computed(
  () =>
    selectionSource(props.entry.field, options.value) || fieldRelation(definition.value.relations, props.entry.field.id)
)
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    }))
  }))
)
</script>
<template>
  <SelectionField
    v-if="selection && applicationId"
    v-model="value"
    :application-id="applicationId"
    :object-id="entry.objectId"
    :field-id="entry.field.id!"
    :detail-id="entry.detailId"
    preview
    placeholder="请选择固定值"
  />
  <RecordQueryField
    v-else
    v-model="value"
    :field="entry.field"
    :options="options"
    :choices="(options?.options || []).filter(o => !o.disabled).map(o => ({ value: o.code, label: o.label }))"
  />
</template>
