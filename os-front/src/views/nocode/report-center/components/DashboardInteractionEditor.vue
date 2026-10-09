<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import DashboardInputValueEditor from './DashboardInputValueEditor.vue'
import { datasetKey } from '@/nocode/report-dataset-editor'
import type { DatasetSource } from '@/types/nocode/report-center'
import type { DashboardChart, DashboardFilter, DashboardFilterKind } from '@/types/nocode/report-dashboard'

const props = withDefaults(
  defineProps<{
    open: boolean
    charts: DashboardChart[]
    filters?: DashboardFilter[] | null
    fields: Record<string, DatasetSource['fields']>
    fieldErrors: Record<string, string>
    loading?: boolean
  }>(),
  { filters: () => [], loading: false }
)
const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'apply', filters: DashboardFilter[]): void
  (event: 'retry'): void
}>()
const editorElement = ref<HTMLElement>()
const activeFilterId = ref('')
const mappingSearch = ref('')
const mappingScope = ref('ALL')
const referenceChartId = ref<string>()
const mappingFeedback = ref('')
const filters = ref<DashboardFilter[]>([]),
  error = ref(''),
  initial = ref('')
const activeFilters = computed(() =>
  filters.value.map((filter, index) => ({ filter, index })).filter(item => item.filter.id === activeFilterId.value)
)
const filterOptions = computed(() =>
  filters.value.map((filter, index) => ({
    value: filter.id,
    label: `${index + 1}. ${filter.name || '未命名筛选'} · ${filter.mappings.length} 张图表`
  }))
)
const activeFilter = computed(() => filters.value.find(filter => filter.id === activeFilterId.value))
/** 状态只判断固定版本字段是否可用；跨字段类型相容性继续由服务端最终校验。 */
const mappingRows = computed(() => {
  const filter = activeFilter.value
  if (!filter) return []
  const rows = props.charts.map(chart => {
    const fieldId = mappedField(filter, chart.id)
    const field = eligibleFields(chart.id, filter.kind).find(item => item.id === fieldId)
    const issue = props.fieldErrors[chart.id]
      ? '字段加载失败，请重试后配置'
      : fieldId && !field
        ? '字段已不可用，请重新选择或清除映射'
        : ''
    return {
      chart,
      chartId: chart.id,
      title: chart.title,
      fieldId,
      fieldName: field?.name || '',
      issue,
      status: issue ? 'ISSUE' : fieldId ? 'MAPPED' : 'UNMAPPED'
    }
  })
  return [
    ...rows,
    ...filter.mappings
      .filter(mapping => !props.charts.some(chart => chart.id === mapping.chartId))
      .map(mapping => ({
        chart: undefined,
        chartId: mapping.chartId,
        title: '已移除的图表',
        fieldId: mapping.fieldId,
        fieldName: '',
        issue: '图表已不存在，请删除此映射',
        status: 'ISSUE'
      }))
  ]
})
const mappingScopes = computed(() => [
  { value: 'ALL', label: `全部 ${mappingRows.value.length}` },
  { value: 'MAPPED', label: `已映射 ${mappingRows.value.filter(row => row.status === 'MAPPED').length}` },
  { value: 'UNMAPPED', label: `未映射 ${mappingRows.value.filter(row => row.status === 'UNMAPPED').length}` },
  { value: 'ISSUE', label: `需处理 ${mappingRows.value.filter(row => row.status === 'ISSUE').length}` }
])
const visibleMappingRows = computed(() => {
  const search = mappingSearch.value.trim().toLocaleLowerCase()
  return mappingRows.value.filter(
    row =>
      (mappingScope.value === 'ALL' || row.status === mappingScope.value) &&
      (!search || `${row.title} ${row.fieldName}`.toLocaleLowerCase().includes(search))
  )
})
const referenceOptions = computed(() =>
  mappingRows.value
    .filter(row => row.status === 'MAPPED')
    .map(row => ({
      value: row.chartId,
      label: `${row.title} · ${row.fieldName} · V${row.chart!.dataset.versionNo}`
    }))
)
watch(
  activeFilterId,
  () => {
    mappingSearch.value = ''
    mappingScope.value = 'ALL'
    referenceChartId.value = undefined
    mappingFeedback.value = ''
  },
  { flush: 'sync' }
)
watch(
  referenceOptions,
  options => {
    if (!options.some(option => option.value === referenceChartId.value)) referenceChartId.value = options[0]?.value
  },
  { immediate: true }
)
const fillTargets = computed(() => {
  const source = mappingRows.value.find(row => row.chartId === referenceChartId.value && row.status === 'MAPPED')
  if (!source?.chart || !activeFilter.value) return []
  const fixed = source.chart.dataset
  return mappingRows.value.filter(row => {
    const target = row.chart?.dataset
    return (
      row.status === 'UNMAPPED' &&
      target?.id === fixed.id &&
      target.versionNo === fixed.versionNo &&
      target.checksum === fixed.checksum &&
      eligibleFields(row.chartId, activeFilter.value!.kind).some(field => field.id === source.fieldId)
    )
  })
})
watch([activeFilterId, error], async () => {
  await nextTick()
  editorElement.value?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
})
const kinds: { value: DashboardFilterKind; label: string }[] = [
  { value: 'TEXT', label: '文本搜索' },
  { value: 'SELECT', label: '单选' },
  { value: 'MULTISELECT', label: '多选' },
  { value: 'NUMBER_RANGE', label: '数字范围' },
  { value: 'DATE_RANGE', label: '日期范围' }
]

watch(
  () => props.open,
  open => {
    if (open) {
      filters.value = JSON.parse(JSON.stringify(props.filters || []))
      activeFilterId.value = filters.value[0]?.id || ''
      error.value = ''
      initial.value = JSON.stringify(filters.value)
      mappingSearch.value = ''
      mappingScope.value = 'ALL'
      mappingFeedback.value = ''
    }
  },
  { immediate: true }
)

function addFilter() {
  const id = datasetKey('filter')
  filters.value.push({ id, name: '', kind: 'SELECT', mappings: [] })
  activeFilterId.value = id
  error.value = ''
}
function removeFilter(index: number) {
  filters.value.splice(index, 1)
  activeFilterId.value = filters.value[Math.min(index, filters.value.length - 1)]?.id || ''
  error.value = ''
}
function eligibleFields(chartId: string, kind: DashboardFilterKind) {
  return (props.fields[chartId] || []).filter(field => kind === 'NUMBER_RANGE' || field.role === 'DIMENSION')
}
function mappedField(filter: DashboardFilter, chartId: string) {
  return filter.mappings.find(mapping => mapping.chartId === chartId)?.fieldId
}
function fieldOptions(filter: DashboardFilter, chartId: string) {
  const fields = eligibleFields(chartId, filter.kind),
    current = mappedField(filter, chartId),
    options = fields.map(field => ({
      value: field.id,
      label: `${field.name}（${field.role === 'MEASURE' ? '度量' : '维度'}）`,
      disabled: false
    }))
  if (current && !fields.some(field => field.id === current)) {
    options.unshift({ value: current, label: `字段不可用（${current}）`, disabled: true })
  }
  return options
}
function setMapping(filter: DashboardFilter, chartId: string, fieldId?: string) {
  filter.mappings = filter.mappings.filter(mapping => mapping.chartId !== chartId)
  if (fieldId) filter.mappings.push({ chartId, fieldId })
  error.value = ''
  mappingFeedback.value = ''
}
/** 参照映射由用户明确选择，仅补齐完全相同固定来源的空映射。 */
function matchSameDataset(filter: DashboardFilter) {
  if (props.loading || !referenceChartId.value) return
  const fieldId = mappedField(filter, referenceChartId.value)
  const targets = [...fillTargets.value]
  if (!fieldId || !targets.length) return
  for (const target of targets) setMapping(filter, target.chartId, fieldId)
  mappingFeedback.value = `已补齐 ${targets.length} 张图表，已有映射保持不变。`
}
function showAllMappings() {
  mappingScope.value = 'ALL'
  mappingSearch.value = ''
}
async function close() {
  if (JSON.stringify(filters.value) !== initial.value) {
    const discard = await new Promise<boolean>(resolve =>
      Modal.confirm({
        title: '放弃尚未应用的公共筛选修改？',
        okText: '放弃修改',
        cancelText: '继续配置',
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    )
    if (!discard) return
  }
  emit('update:open', false)
}
function apply() {
  error.value = ''
  if (props.loading) return
  const names = new Set<string>()
  for (const filter of filters.value) {
    const name = filter.name.trim()
    if (!name || names.has(name)) {
      activeFilterId.value = filter.id
      error.value = '请填写每个筛选的名称，且名称不能重复'
      return
    }
    names.add(name)
    const initial = filter.defaultValue
    if (initial && !initial.values?.length && !initial.from && !initial.to) {
      activeFilterId.value = filter.id
      error.value = `请填写「${name}」的默认值，或关闭默认值`
      return
    }
    if (!filter.mappings.length) {
      activeFilterId.value = filter.id
      showAllMappings()
      error.value = `「${name}」至少需要映射一张图表`
      return
    }
    for (const mapping of filter.mappings) {
      if (
        props.fieldErrors[mapping.chartId] ||
        !eligibleFields(mapping.chartId, filter.kind).some(field => field.id === mapping.fieldId)
      ) {
        activeFilterId.value = filter.id
        mappingSearch.value = ''
        mappingScope.value = 'ISSUE'
        error.value = `请修正「${name}」的不可用字段映射，或取消该图表的映射`
        return
      }
    }
  }
  emit(
    'apply',
    filters.value.map(filter => ({ ...filter, name: filter.name.trim(), mappings: [...filter.mappings] }))
  )
  emit('update:open', false)
}
</script>

<template>
  <OsModalForm
    :open="open"
    title="公共筛选配置"
    display-mode="drawer"
    :width="640"
    :allow-switch-display="false"
    :resizable="false"
    :loading="loading"
    :disabled="loading"
    :wrap-form="false"
    ok-text="应用公共筛选"
    @ok="apply"
    @cancel="close"
  >
    <template #formItems>
      <div ref="editorElement" class="dialog-filter-editor">
        <a-alert
          type="info"
          message="逐张选择参与筛选的字段；未映射的图表保持原有查询。映射字段须在各图表的固定数据集版本中存在，且类型相容。"
          show-icon
        />
        <a-alert v-if="error" :message="error" type="error" show-icon />
        <a-alert v-if="Object.keys(fieldErrors).length" type="warning" show-icon message="部分图表字段加载失败">
          <template #description>
            <div v-for="chart in charts.filter(item => fieldErrors[item.id])" :key="chart.id">
              {{ chart.title }}：{{ fieldErrors[chart.id] }}
            </div>
          </template>
          <template #action>
            <a-button type="link" :loading="loading" @click="emit('retry')">重新加载字段</a-button>
          </template>
        </a-alert>
        <a-skeleton v-if="loading" active :paragraph="{ rows: 3 }" />
        <template v-else>
          <a-empty v-if="!filters.length" description="添加公共筛选，并为需要响应的图表映射字段" />
          <div class="filter-navigation">
            <a-select
              v-if="filters.length"
              v-model:value="activeFilterId"
              aria-label="选择要配置的筛选"
              :options="filterOptions"
              show-search
              option-filter-prop="label"
            />
            <a-button :disabled="!charts.length" @click="addFilter">添加公共筛选</a-button>
          </div>
          <a-card
            v-for="{ filter, index } in activeFilters"
            :key="filter.id"
            size="small"
            :title="`筛选 ${index + 1} / ${filters.length}`"
          >
            <template #extra>
              <a-button type="link" danger :aria-label="`移除筛选${index + 1}`" @click="removeFilter(index)">
                移除
              </a-button>
            </template>
            <a-form layout="vertical">
              <a-form-item label="筛选名称" required>
                <a-input
                  v-model:value="filter.name"
                  aria-label="筛选名称"
                  placeholder="例如：所属公司"
                  :maxlength="80"
                />
              </a-form-item>
              <a-form-item label="筛选类型" required>
                <a-select
                  v-model:value="filter.kind"
                  aria-label="筛选类型"
                  :options="kinds"
                  @change="filter.defaultValue = null"
                />
              </a-form-item>
              <a-form-item label="默认值">
                <a-switch
                  :checked="!!filter.defaultValue"
                  :aria-label="`${filter.name || '筛选' + (index + 1)}启用默认值`"
                  @change="(enabled: boolean) => (filter.defaultValue = enabled ? {} : null)"
                />
                <p class="dialog-filter-help">首次打开和重置筛选时恢复此值，查看者仍可修改或清空。</p>
                <DashboardInputValueEditor
                  v-if="filter.defaultValue"
                  v-model="filter.defaultValue"
                  :kind="filter.kind"
                  :label="`${filter.name || '筛选' + (index + 1)}默认值`"
                />
              </a-form-item>
              <section class="filter-mappings" aria-label="筛选作用图表">
                <h3>作用图表</h3>
                <div class="filter-mapping-batch">
                  <a-form-item label="参照映射">
                    <a-select
                      v-model:value="referenceChartId"
                      aria-label="批量映射参照图表"
                      :options="referenceOptions"
                      :disabled="!referenceOptions.length"
                      placeholder="先为一张图表选择字段"
                      show-search
                      option-filter-prop="label"
                      @change="mappingFeedback = ''"
                    />
                  </a-form-item>
                  <a-button :disabled="!fillTargets.length || loading" @click="matchSameDataset(filter)">
                    补齐 {{ fillTargets.length }} 张图表
                  </a-button>
                </div>
                <p class="dialog-filter-help">仅补齐同数据集、同版本、同字段的空映射；已有映射保留。</p>
                <p v-if="mappingFeedback" role="status" class="filter-mapping-feedback">{{ mappingFeedback }}</p>
                <a-input
                  v-model:value="mappingSearch"
                  aria-label="搜索作用图表"
                  placeholder="搜索图表或已映射字段"
                  allow-clear
                />
                <a-radio-group v-model:value="mappingScope" aria-label="映射状态" class="filter-mapping-scopes">
                  <a-radio-button v-for="scope in mappingScopes" :key="scope.value" :value="scope.value">
                    {{ scope.label }}
                  </a-radio-button>
                </a-radio-group>
                <a-empty v-if="!visibleMappingRows.length" description="没有符合条件的图表">
                  <a-button type="link" @click="showAllMappings">查看全部图表</a-button>
                </a-empty>
                <a-form-item
                  v-for="row in visibleMappingRows"
                  :key="row.chartId"
                  :label="row.title"
                  :validate-status="row.issue ? 'error' : undefined"
                  :help="row.issue || undefined"
                >
                  <a-select
                    v-if="row.chart"
                    :value="row.fieldId"
                    :aria-label="`${filter.name || '筛选' + (index + 1)}映射${row.title}`"
                    :aria-invalid="!!row.issue"
                    :options="fieldOptions(filter, row.chartId)"
                    :disabled="!!fieldErrors[row.chartId]"
                    placeholder="不参与此筛选"
                    allow-clear
                    show-search
                    option-filter-prop="label"
                    @change="(fieldId?: string) => setMapping(filter, row.chartId, fieldId)"
                  />
                  <a-button v-else size="small" @click="setMapping(filter, row.chartId)">删除失效映射</a-button>
                </a-form-item>
              </section>
            </a-form>
          </a-card>
        </template>
      </div>
    </template>
  </OsModalForm>
</template>

<style scoped>
.filter-navigation {
  display: flex;
  gap: var(--spacing-sm);
}
.filter-navigation .ant-select {
  flex: 1;
  min-width: 0;
}
.dialog-filter-editor {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}
.filter-mappings h3 {
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-base);
}
.filter-mapping-batch {
  display: flex;
  gap: var(--spacing-sm);
  align-items: end;
}
.filter-mapping-batch .ant-form-item {
  flex: 1;
  min-width: 0;
  margin-bottom: 0;
}
.filter-mapping-batch + .dialog-filter-help {
  margin-top: var(--spacing-sm);
}
.filter-mapping-feedback {
  color: var(--brand);
}
.filter-mapping-scopes {
  display: flex;
  flex-wrap: wrap;
  margin: var(--spacing-md) 0;
}
.dialog-filter-help {
  margin-bottom: var(--spacing-lg);
  color: var(--text-secondary);
}
</style>
