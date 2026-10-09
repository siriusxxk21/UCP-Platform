<script setup lang="ts">
import { ref, watch } from 'vue'
import { useReportDashboard } from '@/nocode/report-context'
import { useNocodePlatform } from '@/nocode/platform'
import { recordFilterValue } from '@/nocode/record-form'
import { errorMessage } from '@/nocode/data-center'
import type { RecordModel } from '@/types/nocode/runtime'
import { rangePickerPresets } from '@/nocode/relative-date'
import ReportFilterInput from './ReportFilterInput.vue'
const props = defineProps<{ applicationId: string }>()
const dashboard = useReportDashboard()!
const api = useNocodePlatform().runtime
const models = ref<Record<string, RecordModel>>({}),
  draft = ref<Record<string, unknown>>({}),
  error = ref(''),
  busy = ref(false)
let generation = 0
watch(
  () => [props.applicationId, dashboard.definitions.value],
  async () => {
    const turn = ++generation
    busy.value = true
    error.value = ''
    models.value = {}
    draft.value = {}
    dashboard.values.value = {}
    try {
      const result: Record<string, RecordModel> = {}
      for (const id of new Set(dashboard.definitions.value.map(f => f.objectId)))
        result[id] = await api.model(props.applicationId, id)
      if (turn === generation) models.value = result
    } catch (e) {
      if (turn === generation) error.value = errorMessage(e)
    } finally {
      if (turn === generation) busy.value = false
    }
  },
  { immediate: true }
)
function apply() {
  error.value = ''
  try {
    const output: Record<string, unknown> = {}
    for (const f of dashboard.definitions.value) {
      const v = draft.value[f.id]
      if (v == null || v === '') continue
      const field = models.value[f.objectId]?.object.fields.find(field => field.id === f.fieldId)
      if (!field) throw new Error('筛选字段不可用，请重新加载页面')
      output[f.id] = f.dateRange ? v : recordFilterValue(field, String(v))
    }
    dashboard.values.value = output
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function reset() {
  draft.value = {}
  dashboard.values.value = {}
  error.value = ''
}
</script>
<template>
  <a-card v-if="dashboard.definitions.value.length" size="small" class="dashboard-filters">
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-form layout="inline" @submit.prevent="apply">
      <a-form-item v-for="f in dashboard.definitions.value" :key="f.id" :label="f.name">
        <a-range-picker
          v-if="f.dateRange"
          v-model:value="draft[f.id]"
          value-format="YYYY-MM-DD"
          :presets="rangePickerPresets()"
        />
        <ReportFilterInput
          v-else-if="models[f.objectId]?.object.fields.some(v => v.id === f.fieldId)"
          v-model="draft[f.id]"
          :model="models[f.objectId]"
          :field="models[f.objectId].object.fields.find(v => v.id === f.fieldId)!"
          :application-id="applicationId"
        />
        <span v-else class="hint">{{ busy ? '加载中…' : '字段不可用' }}</span>
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" :disabled="busy" @click="apply">查询</a-button>
          <a-button @click="reset">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>
  </a-card>
</template>
<style scoped>
.dashboard-filters {
  margin-bottom: 16px;
  border-radius: 8px;
}
.dashboard-filters :deep(.ant-form) {
  row-gap: 12px;
}
.dashboard-filters :deep(.ant-select),
.dashboard-filters :deep(.ant-input-affix-wrapper) {
  min-width: 180px;
}
.hint {
  color: #64748b;
}
</style>
