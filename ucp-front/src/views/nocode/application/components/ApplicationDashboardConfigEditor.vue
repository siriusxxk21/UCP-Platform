<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  applicationDashboardDetailViews,
  applicationDashboardInputFields,
  applicationDashboardRecordIdAvailable,
  prepareApplicationDashboard
} from '@/nocode/application-dashboard'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import {
  ApplicationDashboardInputSource,
  type ApplicationDashboardCandidate,
  type ApplicationDashboardCatalog,
  type ApplicationDashboardConfig,
  type ApplicationDashboardInputBinding
} from '@/types/nocode/application-dashboard'

const props = defineProps<{
  modelValue: ApplicationDashboardConfig
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
  readOnly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: ApplicationDashboardConfig] }>()
const api = useNocodePlatform().applications
const candidates = ref<ApplicationDashboardCandidate[]>([])
const candidateLoading = ref(false),
  candidateError = ref(''),
  search = ref(''),
  pageNo = ref(1),
  total = ref(0)
const pageSize = 20
const catalog = ref<ApplicationDashboardCatalog>()
const catalogLoading = ref(false),
  catalogError = ref('')
let candidateGeneration = 0,
  catalogGeneration = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
const objects = computed(() =>
  Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum }))
)
const inputs = computed(() => props.modelValue.inputBindings || [])
const details = computed(() => props.modelValue.detailViews || [])
const currentCandidate = computed(() => candidates.value.find(value => value.id === props.modelValue.dashboard?.id))
const newerCandidate = computed(() => {
  const value = currentCandidate.value,
    fixed = props.modelValue.dashboard
  return value && fixed && value.versionNo > fixed.versionNo ? value : undefined
})
const candidateOptions = computed(() => {
  const options = candidates.value.map(value => ({
    label: `${value.name} · v${value.versionNo} · ${value.chartCount} 个图表`,
    value: value.id
  }))
  const fixed = props.modelValue.dashboard
  if (fixed && !options.some(value => value.value === fixed.id))
    options.unshift({ value: fixed.id, label: `${catalog.value?.content.name || '已固定看板'} · v${fixed.versionNo}` })
  return options
})
const objectOptions = computed(() =>
  Object.values(props.objects).map(value => ({ value: value.objectId, label: value.definition.objectName }))
)
const filterOptions = computed(() =>
  (catalog.value?.content.filters || []).map(value => ({ value: value.id, label: value.name }))
)
const chartOptions = computed(() =>
  (catalog.value?.content.charts || []).map(value => ({ value: value.id, label: value.title }))
)
const validationError = computed(() => {
  if (!catalog.value || catalogLoading.value || catalogError.value) return ''
  try {
    prepareApplicationDashboard(props.modelValue, catalog.value, props.objects, props.resources)
    return ''
  } catch (e) {
    return errorMessage(e)
  }
})
const ready = computed(() => !!catalog.value && !catalogLoading.value && !catalogError.value && !validationError.value)
function update(value: Partial<ApplicationDashboardConfig>) {
  if (!props.readOnly) emit('update:modelValue', { ...props.modelValue, ...value })
}
async function loadCandidates() {
  const generation = ++candidateGeneration
  candidateLoading.value = true
  candidateError.value = ''
  try {
    const result = await api.dashboardCatalogPage({ pageNo: pageNo.value, pageSize, search: search.value || undefined })
    if (generation !== candidateGeneration) return
    candidates.value = result.list
    total.value = result.total
  } catch (e) {
    if (generation === candidateGeneration) candidateError.value = errorMessage(e)
  } finally {
    if (generation === candidateGeneration) candidateLoading.value = false
  }
}
function searchCandidates(value: string) {
  search.value = value
  pageNo.value = 1
  clearTimeout(searchTimer)
  searchTimer = setTimeout(loadCandidates, 250)
}
function changeCandidatePage(value: number) {
  pageNo.value = value
  void loadCandidates()
}
async function loadCatalog() {
  const generation = ++catalogGeneration
  catalog.value = undefined
  catalogError.value = ''
  const reference = props.modelValue.dashboard
  if (!reference) {
    catalogLoading.value = false
    return
  }
  catalogLoading.value = true
  try {
    const result = await api.dashboardCatalog({ reference: { ...reference }, objects: objects.value })
    if (generation === catalogGeneration) catalog.value = result
  } catch (e) {
    if (generation === catalogGeneration) catalogError.value = errorMessage(e)
  } finally {
    if (generation === catalogGeneration) catalogLoading.value = false
  }
}
function selectCandidate(id: string) {
  const value = candidates.value.find(value => value.id === id)
  if (!value || props.readOnly || value.id === props.modelValue.dashboard?.id) return
  const apply = () =>
    update({
      dashboard: { id: value.id, versionNo: value.versionNo, checksum: value.checksum },
      inputBindings: [],
      detailViews: []
    })
  if (inputs.value.length || details.value.length)
    Modal.confirm({
      title: '更换看板并清空输入与明细绑定？',
      content: '当前绑定依赖原看板的筛选与图表，选择新看板后需要重新配置。',
      okText: '更换看板',
      cancelText: '保留当前配置',
      onOk: apply
    })
  else apply()
}
function upgrade() {
  const value = newerCandidate.value
  if (!value || props.readOnly) return
  Modal.confirm({
    title: `将看板引用升级到 v${value.versionNo}？`,
    content: '输入和明细绑定将保留并重新校验；失效绑定须处理后才能应用到草稿。',
    okText: '升级并校验',
    cancelText: '保留固定版本',
    onOk: () => update({ dashboard: { id: value.id, versionNo: value.versionNo, checksum: value.checksum } })
  })
}
function updateInput(index: number, value: Partial<ApplicationDashboardInputBinding>) {
  update({ inputBindings: inputs.value.map((binding, i) => (i === index ? { ...binding, ...value } : binding)) })
}
function sourceOptions(binding: ApplicationDashboardInputBinding) {
  const filter = catalog.value?.content.filters?.find(value => value.id === binding.filterId)
  const object = props.objects[props.modelValue.contextObjectId || '']
  return [
    { value: ApplicationDashboardInputSource.PARAMETER, label: '外部参数' },
    {
      value: ApplicationDashboardInputSource.RECORD_ID,
      label: '当前记录编号',
      disabled: !object || !filter || !catalog.value || !applicationDashboardRecordIdAvailable(catalog.value, filter)
    },
    {
      value: ApplicationDashboardInputSource.RECORD_FIELD,
      label: '当前记录字段',
      disabled:
        !object || !filter || !catalog.value || !applicationDashboardInputFields(catalog.value, filter, object).length
    }
  ]
}
function inputFields(binding: ApplicationDashboardInputBinding) {
  const filter = catalog.value?.content.filters?.find(value => value.id === binding.filterId)
  return filter && catalog.value
    ? applicationDashboardInputFields(catalog.value, filter, props.objects[props.modelValue.contextObjectId || '']).map(
        value => ({ value: value.id || '', label: value.name })
      )
    : []
}
function detailOptions(chartId: string) {
  return catalog.value
    ? applicationDashboardDetailViews(catalog.value, chartId, props.resources).map(value => ({
        value: value.id,
        label: value.name
      }))
    : []
}
function updateDetail(index: number, value: { chartId?: string; viewId?: string }) {
  update({ detailViews: details.value.map((binding, i) => (i === index ? { ...binding, ...value } : binding)) })
}
watch(() => JSON.stringify([props.modelValue.dashboard, objects.value]), loadCatalog, {
  immediate: true,
  flush: 'sync'
})
onMounted(loadCandidates)
onBeforeUnmount(() => {
  candidateGeneration++
  catalogGeneration++
  clearTimeout(searchTimer)
})
defineExpose({
  isReady: () => ready.value,
  getConfig: () => {
    if (catalogLoading.value) throw new Error('请等待看板固定目录校验完成')
    if (catalogError.value) throw new Error(catalogError.value)
    return prepareApplicationDashboard(props.modelValue, catalog.value, props.objects, props.resources)
  }
})
</script>
<template>
  <section class="dashboard-config-editor">
    <a-alert
      type="info"
      show-icon
      message="引用已发布看板，保存固定版本"
      description="候选按看板查看权限加载。应用中仍需先引用数据集涉及的对象；运行时同时校验应用、看板、数据集及对象的数据权限。"
    />
    <a-form-item label="已发布看板" required>
      <a-select
        :value="modelValue.dashboard?.id"
        :options="candidateOptions"
        :loading="candidateLoading"
        :disabled="readOnly"
        show-search
        :filter-option="false"
        placeholder="搜索并选择已发布看板"
        @search="searchCandidates"
        @change="selectCandidate"
      />
    </a-form-item>
    <div class="dashboard-config-toolbar">
      <a-button :loading="candidateLoading" @click="loadCandidates">刷新候选</a-button>
      <a-pagination
        v-if="total > pageSize"
        :current="pageNo"
        :page-size="pageSize"
        :total="total"
        simple
        @change="changeCandidatePage"
      />
    </div>
    <a-alert v-if="candidateError" type="warning" show-icon :message="candidateError" />
    <a-empty
      v-if="!candidateLoading && !candidateError && !candidates.length && !modelValue.dashboard"
      description="暂无可查看的已发布看板，请先在报表中心发布看板"
    />
    <div v-if="modelValue.dashboard" class="dashboard-config-pin">
      <a-tag color="blue">固定版本 v{{ modelValue.dashboard.versionNo }}</a-tag>
      <a-typography-text type="secondary" :title="modelValue.dashboard.checksum">
        校验和 {{ modelValue.dashboard.checksum.slice(0, 12) }}…
      </a-typography-text>
      <a-button v-if="newerCandidate && !readOnly" type="link" @click="upgrade">
        升级到 v{{ newerCandidate.versionNo }}
      </a-button>
    </div>
    <a-skeleton v-if="catalogLoading" active :paragraph="{ rows: 3 }" />
    <template v-else-if="catalogError">
      <a-alert type="error" show-icon :message="catalogError" />
      <a-button @click="loadCatalog">重新校验固定目录</a-button>
    </template>
    <template v-else-if="catalog">
      <p class="dashboard-config-hint">
        {{ catalog.content.name }} · {{ catalog.content.charts.length }} 个图表。版本升级须主动确认。
      </p>
      <a-form-item label="当前记录对象（可选）">
        <a-select
          :value="modelValue.contextObjectId || undefined"
          :options="objectOptions"
          :disabled="readOnly"
          allow-clear
          placeholder="工作台参数无需绑定记录对象"
          @change="(value: string | undefined) => update({ contextObjectId: value || null })"
        />
      </a-form-item>
      <p class="dashboard-config-hint">
        绑定记录对象后，只能放入相同当前记录对象的业务页面。更换对象后，已有记录输入需重新校验。
      </p>
      <a-divider orientation="left">输入绑定</a-divider>
      <p class="dashboard-config-hint">
        绑定后的筛选由参数或当前记录提供。参数名用于运行端输入；未绑定筛选仍由查看者填写。
      </p>
      <div v-for="(binding, index) in inputs" :key="index" class="dashboard-config-binding">
        <a-row :gutter="16">
          <a-col :xs="24" :md="8">
            <a-form-item label="看板筛选" required>
              <a-select
                :value="binding.filterId || undefined"
                :options="filterOptions"
                :disabled="readOnly"
                placeholder="选择固定看板筛选"
                @change="(value: string) => updateInput(index, { filterId: value })"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="8">
            <a-form-item label="输入来源" required>
              <a-select
                :value="binding.source"
                :options="sourceOptions(binding)"
                :disabled="readOnly"
                @change="
                  (value: ApplicationDashboardInputSource) =>
                    updateInput(index, { source: value, parameter: null, fieldId: null })
                "
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="8">
            <a-form-item v-if="binding.source === ApplicationDashboardInputSource.PARAMETER" label="参数名" required>
              <a-input
                :value="binding.parameter || ''"
                :disabled="readOnly"
                :maxlength="64"
                placeholder="字母开头，例如 customerId"
                @update:value="(value: string) => updateInput(index, { parameter: value })"
              />
            </a-form-item>
            <a-form-item
              v-else-if="binding.source === ApplicationDashboardInputSource.RECORD_FIELD"
              label="记录字段"
              required
            >
              <a-select
                :value="binding.fieldId || undefined"
                :options="inputFields(binding)"
                :disabled="readOnly"
                placeholder="选择兼容标量字段"
                @change="(value: string) => updateInput(index, { fieldId: value })"
              />
            </a-form-item>
            <p v-else class="dashboard-config-hint">运行时使用当前记录编号。</p>
          </a-col>
        </a-row>
        <a-button
          v-if="!readOnly"
          danger
          type="link"
          @click="update({ inputBindings: inputs.filter((_, i) => i !== index) })"
        >
          移除输入
        </a-button>
      </div>
      <a-button
        v-if="!readOnly"
        :disabled="inputs.length >= 10 || !filterOptions.length"
        @click="
          update({
            inputBindings: [
              ...inputs,
              { filterId: '', source: ApplicationDashboardInputSource.PARAMETER, parameter: '', fieldId: null }
            ]
          })
        "
      >
        添加输入绑定
      </a-button>
      <a-empty v-if="!filterOptions.length" description="该固定版本未配置看板筛选" />
      <a-divider orientation="left">图表业务明细视图</a-divider>
      <p class="dashboard-config-hint">
        仅能选择本应用中与图表数据集根对象一致的数据视图。没有绑定的图表提供只读明细。
      </p>
      <div v-for="(binding, index) in details" :key="index" class="dashboard-config-binding">
        <a-row :gutter="16">
          <a-col :xs="24" :md="12">
            <a-form-item label="图表" required>
              <a-select
                :value="binding.chartId || undefined"
                :options="chartOptions"
                :disabled="readOnly"
                placeholder="选择图表"
                @change="(value: string) => updateDetail(index, { chartId: value, viewId: '' })"
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="12">
            <a-form-item label="本应用数据视图" required>
              <a-select
                :value="binding.viewId || undefined"
                :options="detailOptions(binding.chartId)"
                :disabled="readOnly"
                placeholder="选择同一根对象的视图"
                @change="(value: string) => updateDetail(index, { viewId: value })"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-button
          v-if="!readOnly"
          danger
          type="link"
          @click="update({ detailViews: details.filter((_, i) => i !== index) })"
        >
          移除明细绑定
        </a-button>
      </div>
      <a-button
        v-if="!readOnly"
        :disabled="details.length >= 30"
        @click="update({ detailViews: [...details, { chartId: '', viewId: '' }] })"
      >
        添加业务明细视图
      </a-button>
      <a-alert v-if="validationError" type="warning" show-icon :message="validationError" />
    </template>
  </section>
</template>
<style scoped>
.dashboard-config-editor {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}
.dashboard-config-editor :deep(.ant-form-item) {
  margin-bottom: 0;
}
.dashboard-config-toolbar,
.dashboard-config-pin {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.dashboard-config-toolbar {
  justify-content: space-between;
}
.dashboard-config-hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.dashboard-config-binding {
  padding: var(--spacing-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
</style>
