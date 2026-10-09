<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import type { DashboardFilter, DashboardFilterValue, DashboardQuery } from '@/types/nocode/report-dashboard'
const props = defineProps<{
  filter: DashboardFilter
  query: DashboardQuery
  value?: DashboardFilterValue
  transport?: DashboardRuntimeTransport
}>()
const emit = defineEmits<{ change: [value: DashboardFilterValue]; fault: [cause: unknown] }>()
const api = useNocodePlatform().reportCenter
const text = ref(''),
  from = ref<string>(),
  to = ref<string>()
const options = ref<{ value: string; label: string }[]>([]),
  busy = ref(false),
  error = ref('')
const search = ref(''),
  pageNo = ref(1),
  total = ref(0),
  dropdownOpen = ref(false)
const selectedLabels = ref<Record<string, string>>({})
let generation = 0,
  controller: AbortController | undefined,
  timer: ReturnType<typeof setTimeout> | undefined
// JSON 编码区分业务字符串 "null" 与真实空值，不能用易碰撞的哨兵键。
const selected = computed(() => (props.value?.values || []).map(v => JSON.stringify(v)))
const choices = computed(() => [
  ...selected.value
    .filter(v => !options.value.some(o => o.value === v))
    .map(v => ({
      value: v,
      label:
        selectedLabels.value[v] ?? (JSON.parse(v) === null ? '空值' : JSON.parse(v) === '' ? '空文本' : JSON.parse(v))
    })),
  ...options.value
])
watch(
  () => props.value,
  value => {
    text.value = value?.values?.[0] || ''
    from.value = value?.from || undefined
    to.value = value?.to || undefined
  },
  { immediate: true, deep: true }
)
function cancel() {
  generation++
  controller?.abort()
  if (timer) clearTimeout(timer)
  busy.value = false
}
watch(
  () => JSON.stringify(props.query),
  () => {
    cancel()
    options.value = []
    pageNo.value = 1
    total.value = 0
    error.value = ''
    if (dropdownOpen.value) void load()
  }
)
async function load(append = false) {
  cancel()
  const g = generation
  controller = new AbortController()
  busy.value = true
  error.value = ''
  const nextPage = append ? pageNo.value + 1 : 1
  try {
    const data = await (props.transport?.options || api.dashboardOptions)(
      { query: props.query, filterId: props.filter.id, pageNo: nextPage, pageSize: 50, search: search.value },
      controller.signal
    )
    if (g !== generation) return
    const next = data.list.map(v => ({ value: JSON.stringify(v.value), label: v.label }))
    options.value = append
      ? [...options.value, ...next.filter(o => !options.value.some(p => p.value === o.value))]
      : next
    total.value = data.total
    pageNo.value = nextPage
    for (const option of next) selectedLabels.value[option.value] = option.label
  } catch (e) {
    if (g === generation) {
      emit('fault', e)
      error.value = errorMessage(e)
    }
  } finally {
    if (g === generation) busy.value = false
  }
}
function find(value: string) {
  cancel()
  search.value = value
  timer = setTimeout(() => void load(), 300)
}
function choose(value: string | string[] | undefined) {
  const values =
    value === undefined ? [] : (Array.isArray(value) ? value : [value]).map(v => JSON.parse(v) as string | null)
  emit('change', { filterId: props.filter.id, values })
}
function apply() {
  emit(
    'change',
    props.filter.kind === 'TEXT'
      ? { filterId: props.filter.id, values: text.value ? [text.value] : [] }
      : { filterId: props.filter.id, from: from.value || null, to: to.value || null }
  )
}
onScopeDispose(cancel)
</script>
<template>
  <div class="dashboard-filter-control">
    <label>{{ filter.name }}</label>
    <a-input-search
      v-if="filter.kind === 'TEXT'"
      v-model:value="text"
      :aria-label="filter.name"
      allow-clear
      placeholder="输入搜索文本"
      @search="apply"
    >
      <template #enterButton><a-button :aria-label="'应用' + filter.name">应用</a-button></template>
    </a-input-search>
    <a-select
      v-else-if="filter.kind === 'SELECT' || filter.kind === 'MULTISELECT'"
      :value="filter.kind === 'MULTISELECT' ? selected : selected[0]"
      :mode="filter.kind === 'MULTISELECT' ? 'multiple' : undefined"
      :aria-label="filter.name"
      :options="choices"
      allow-clear
      show-search
      :filter-option="false"
      :loading="busy"
      placeholder="全部"
      @change="choose"
      @search="find"
      @dropdown-visible-change="
        (open: boolean) => {
          dropdownOpen = open
          if (open) {
            search = ''
            void load()
          }
        }
      "
    >
      <template #dropdownRender="{ menuNode }">
        <component :is="menuNode" />
        <a-button
          v-if="options.length < total"
          block
          type="link"
          :loading="busy"
          @mousedown.prevent
          @click="load(true)"
        >
          加载更多候选
        </a-button>
      </template>
    </a-select>
    <a-space v-else>
      <template v-if="filter.kind === 'DATE_RANGE'">
        <label :for="filter.id + '-from'" class="dashboard-filter-sr-label">{{ filter.name + '开始日期' }}</label>
        <a-date-picker
          :id="filter.id + '-from'"
          v-model:value="from"
          value-format="YYYY-MM-DD"
          :aria-label="filter.name + '开始日期'"
          placeholder="开始日期"
        />
        <span>至</span>
        <label :for="filter.id + '-to'" class="dashboard-filter-sr-label">{{ filter.name + '结束日期' }}</label>
        <a-date-picker
          :id="filter.id + '-to'"
          v-model:value="to"
          value-format="YYYY-MM-DD"
          :aria-label="filter.name + '结束日期'"
          placeholder="结束日期"
        />
      </template>
      <template v-else>
        <a-input v-model:value="from" :aria-label="filter.name + '下限'" placeholder="下限" allow-clear />
        <span>至</span>
        <a-input v-model:value="to" :aria-label="filter.name + '上限'" placeholder="上限" allow-clear />
      </template>
      <a-button :aria-label="'应用' + filter.name" @click="apply">应用</a-button>
    </a-space>
    <a-alert v-if="error" :message="error" type="error" show-icon />
  </div>
</template>
<style scoped>
.dashboard-filter-control {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: calc(var(--spacing-lg) * 12);
  max-width: 100%;
}
.dashboard-filter-control label {
  color: var(--text-secondary);
}
.dashboard-filter-control :deep(.ant-space) {
  flex-wrap: wrap;
}
.dashboard-filter-control :deep(.ant-input) {
  min-width: calc(var(--spacing-lg) * 5);
}
/* 日期组件的 aria-label 落在容器上，通过原生 label 为实际输入框提供名称。 */
.dashboard-filter-sr-label {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 768px) {
  .dashboard-filter-control {
    min-width: 0;
    width: 100%;
  }
  .dashboard-filter-control :deep(.ant-space),
  .dashboard-filter-control :deep(.ant-space-item),
  .dashboard-filter-control :deep(.ant-picker) {
    max-width: 100%;
  }
  .dashboard-filter-control :deep(.ant-input-search) {
    min-width: 0;
  }
}
</style>
