<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { conversionImpactRoute } from '@/nocode/field-conversion'
import { distinctFieldImpacts, fieldImpactKey } from '@/nocode/field-impact-presentation'
import type { ObjectOperationPreview, ObjectOperationPreviewRequest } from '@/types/nocode/data-center'

const props = defineProps<{ request: ObjectOperationPreviewRequest | null }>()
const emit = defineEmits<{
  checked: [result: ObjectOperationPreview | undefined]
  navigate: [route: string]
}>()
const platform = useNocodePlatform()
const result = ref<ObjectOperationPreview>()
const loading = ref(false)
const error = ref('')
const impacts = computed(() => distinctFieldImpacts(result.value?.impacts || []))
const steps = computed(() => [...new Set(result.value?.steps.map(step => step.trim()).filter(Boolean) || [])])
let generation = 0
async function check() {
  const current = ++generation
  result.value = undefined
  error.value = ''
  emit('checked', undefined)
  loading.value = !!props.request
  if (!props.request) return
  try {
    const response = await platform.dataCenter.operationPreview(props.request)
    if (current === generation) {
      result.value = response
      emit('checked', response)
    }
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.request, check, { immediate: true, deep: true })
onBeforeUnmount(() => {
  generation++
})
</script>

<template>
  <section class="operation-impact" aria-label="操作影响检查">
    <div class="operation-heading">
      <strong>操作前检查</strong>
      <a-button type="link" :disabled="loading" @click="check">重新检查</a-button>
    </div>
    <a-spin v-if="loading" tip="正在检查数据保留范围和引用" />
    <a-alert v-else-if="error" type="error" show-icon :message="error" />
    <template v-else-if="result">
      <a-alert
        class="operation-conclusion"
        :type="result.allowed ? 'info' : 'error'"
        show-icon
        :message="result.summary"
      />
      <ul v-if="result.dataScopes.length" class="operation-data">
        <li
          v-for="scope in result.dataScopes"
          :key="`${scope.schemaName}.${scope.tableName}.${scope.columnName ?? ''}`"
        >
          <strong>{{ scope.name }}</strong>
          ：
          {{ scope.rowCount == null ? '记录数未能核实' : `${scope.rowCount} 条记录` }}
          <span v-if="scope.nonNullCount != null">，本列 {{ scope.nonNullCount }} 条有值</span>
          。
          {{ scope.message }}
        </li>
      </ul>
      <div v-if="impacts.length" class="operation-impacts" aria-label="需要处理的影响">
        <article v-for="impact in impacts" :key="fieldImpactKey(impact)" class="operation-item">
          <div class="impact-location">
            <a-tag :color="impact.blocking ? 'error' : 'warning'">{{ impact.blocking ? '需处理' : '提示' }}</a-tag>
            <strong>{{ [impact.sourceName, impact.location].filter(Boolean).join(' · ') || '需要处理的影响' }}</strong>
            <a-button
              v-if="conversionImpactRoute(impact.route)"
              type="link"
              @click="emit('navigate', conversionImpactRoute(impact.route)!)"
            >
              新标签页处理
            </a-button>
          </div>
          <p>{{ impact.message }}</p>
          <p v-if="impact.resolution && impact.resolution.trim() !== impact.message.trim()" class="muted">
            处理方式：{{ impact.resolution }}
          </p>
        </article>
      </div>
      <a-collapse v-if="steps.length" ghost class="operation-details">
        <a-collapse-panel key="steps" header="后续步骤与生效说明">
          <ol class="operation-data">
            <li v-for="step in steps" :key="step">{{ step }}</li>
          </ol>
        </a-collapse-panel>
      </a-collapse>
      <p class="muted">当前检查不修改数据或配置；确认执行时会重新检查。</p>
    </template>
  </section>
</template>

<style scoped>
.operation-impact {
  margin: 12px 0;
}
.operation-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.operation-item {
  padding: 12px 0;
  border-top: 1px solid var(--border);
}
.impact-location {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
.operation-impacts,
.operation-details {
  margin-top: 12px;
}
.operation-item p {
  margin-bottom: 6px;
}
.operation-data {
  padding-left: 22px;
  margin: 12px 0;
}
.operation-data li {
  margin: 8px 0;
}
.muted {
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
