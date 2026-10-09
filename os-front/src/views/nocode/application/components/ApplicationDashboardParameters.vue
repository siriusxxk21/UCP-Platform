<script setup lang="ts">
import { ref, watch } from 'vue'
import type { DashboardFilter } from '@/types/nocode/report-dashboard'
import type { ApplicationDashboardInputValue } from '@/types/nocode/application-dashboard-runtime'
import { applicationDashboardParameterReady } from '@/nocode/application-dashboard-runtime'
import DashboardInputValueEditor from '@/views/nocode/report-center/components/DashboardInputValueEditor.vue'
const props = defineProps<{ items: { name: string; filter: DashboardFilter }[] }>()
const emit = defineEmits<{ apply: [values: Record<string, ApplicationDashboardInputValue>]; reset: [] }>()
const values = ref<Record<string, ApplicationDashboardInputValue>>({}),
  error = ref('')
watch(
  () => JSON.stringify(props.items),
  () => {
    values.value = Object.fromEntries(props.items.map(item => [item.name, {}]))
    error.value = ''
  },
  { immediate: true }
)
function apply() {
  const parameters: Record<string, ApplicationDashboardInputValue> = {}
  for (const item of props.items) {
    const value = values.value[item.name]!
    if (!applicationDashboardParameterReady(value)) {
      error.value = '请填写' + item.filter.name
      return
    }
    parameters[item.name] = value
  }
  error.value = ''
  emit('apply', parameters)
}
function reset() {
  values.value = Object.fromEntries(props.items.map(item => [item.name, {}]))
  error.value = ''
  emit('reset')
}
</script>
<template>
  <section class="application-dashboard-inputs" aria-label="看板参数">
    <a-form layout="vertical">
      <div class="application-dashboard-fields">
        <a-form-item v-for="item in items" :key="item.name" :label="item.filter.name" required>
          <DashboardInputValueEditor
            v-model="values[item.name]"
            :kind="item.filter.kind"
            :label="item.filter.name + '参数'"
          />
        </a-form-item>
      </div>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-space>
        <a-button type="primary" @click="apply">应用参数</a-button>
        <a-button @click="reset">清空参数</a-button>
      </a-space>
    </a-form>
  </section>
</template>
<style scoped>
.application-dashboard-inputs {
  padding: var(--spacing-lg);
  background: var(--color-bg-container);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  margin-bottom: var(--spacing-lg);
}
.application-dashboard-fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-lg);
}
.application-dashboard-fields :deep(.ant-form-item) {
  min-width: calc(var(--spacing-lg) * 12);
  max-width: 100%;
}
.application-dashboard-fields :deep(.ant-select) {
  min-width: calc(var(--spacing-lg) * 12);
}
</style>
