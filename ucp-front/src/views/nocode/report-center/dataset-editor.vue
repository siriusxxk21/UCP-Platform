<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { emptyScope } from '@/nocode/data-scope'
import { formatReportValue } from '@/nocode/report-presentation'
import { formatDateTime } from '@/utils/format'
import type { DataScope } from '@/types/nocode/data-scope'
import DataScopeEditor from '../components/DataScopeEditor.vue'
import { errorMessage } from '@/nocode/data-center'
import { newDatasetAnalysis, datasetScopeFields } from '@/nocode/report-dataset-editor'
import type { DatasetContent, DatasetDetail, DatasetRelease, ReportObjectVersion } from '@/types/nocode/report-center'
import type { ReportResult, ReportBucket } from '@/types/nocode/report'
import DatasetSourceEditor from './components/DatasetSourceEditor.vue'
import DatasetOptionPicker from './components/DatasetOptionPicker.vue'
import DatasetAnalysisEditor from './components/DatasetAnalysisEditor.vue'
import DatasetAuthorization from './components/DatasetAuthorization.vue'
const platform = useNocodePlatform(),
  api = platform.reportCenter,
  route = useRoute(),
  router = useRouter()
const detail = ref<DatasetDetail>(),
  form = ref<DatasetContent>(),
  baseline = ref('')
const loading = ref(false),
  busy = ref(false),
  sourceBusy = ref(false),
  error = ref(''),
  tab = ref('source')
const catalog = ref<Record<string, ReportObjectVersion>>({})
const authorization = ref<'resource'>()
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== baseline.value)
const replacingSource = computed(() => !!detail.value?.draft.source && !form.value?.source)
const editable = computed(() => platform.hasPermission('nocode:report:update') && !busy.value && !loading.value)
const result = ref<ReportResult>(),
  querying = ref(false),
  previewError = ref(''),
  dimensions = ref<string[]>([])
const filters = ref<DataScope | null>(null)
const filterFields = computed(() => datasetScopeFields(form.value?.source || null, catalog.value))
const buckets = ref<Record<string, ReportBucket>>({})
const dateDimensions = computed(() =>
  datasetScopeFields(form.value?.source || null, catalog.value).filter(
    field => dimensions.value.includes(field.key) && ['DATE', 'DATETIME'].includes(field.type)
  )
)
const releases = ref<DatasetRelease[]>([]),
  historyTotal = ref(0),
  historyPage = ref(1),
  historyBusy = ref(false)
const operation = ref<'publish' | 'restore' | 'status'>(),
  reason = ref(''),
  operationError = ref(''),
  versionNo = ref(0)
let request = 0,
  queryRequest = 0,
  historyRequest = 0
let publishRequest = { revision: -1, id: '' }
function apply(value: DatasetDetail) {
  detail.value = value
  form.value = JSON.parse(JSON.stringify(value.draft)) as DatasetContent
  if (form.value.source) form.value.analysis ??= newDatasetAnalysis()
  baseline.value = JSON.stringify(form.value)
  result.value = undefined
  dimensions.value = dimensions.value.filter(id => form.value?.source?.fields.some(f => f.id === id))
}
async function load() {
  const number = ++request
  queryRequest++
  historyRequest++
  detail.value = undefined
  form.value = undefined
  result.value = undefined
  releases.value = []
  loading.value = true
  error.value = ''
  catalog.value = {}
  buckets.value = {}
  filters.value = null
  operation.value = undefined
  authorization.value = undefined
  try {
    if (typeof route.query.id !== 'string') throw new Error('缺少数据集标识，请从数据集列表进入')
    const value = await api.get(route.query.id)
    if (number === request) apply(value)
  } catch (e) {
    if (number === request) error.value = errorMessage(e)
  } finally {
    if (number === request) loading.value = false
  }
}
async function save() {
  if (!detail.value || !form.value || busy.value || sourceBusy.value || replacingSource.value) return
  busy.value = true
  error.value = ''
  try {
    apply(
      await api.save({
        ...JSON.parse(JSON.stringify(form.value)),
        id: detail.value.id,
        expectedRevision: detail.value.revision
      })
    )
    message.success('草稿已保存')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function resetSource() {
  Modal.confirm({
    title: '更换来源对象？',
    content: '当前所选字段、关联、指标和固定筛选将从编辑内容中清除。保存前可放弃本次修改。',
    okText: '更换来源',
    cancelText: '继续编辑',
    onOk: () => {
      if (form.value && editable.value) {
        form.value.source = null
        form.value.analysis = null
      }
    }
  })
}
async function preview() {
  if (!detail.value || dirty.value || querying.value) return
  const number = ++queryRequest
  querying.value = true
  previewError.value = ''
  result.value = undefined
  try {
    const value = await api.query({
      datasetId: detail.value.id,
      preview: true,
      dimensions: dimensions.value.map(fieldId => ({
        fieldId,
        bucket: dateDimensions.value.some(field => field.key === fieldId) ? buckets.value[fieldId] || 'VALUE' : 'VALUE'
      })),
      filters: filters.value ? JSON.parse(JSON.stringify(filters.value)) : null,
      limit: 100,
      timeZone: form.value?.analysis?.timeZone
    })
    if (number === queryRequest && !dirty.value) result.value = value
  } catch (e) {
    if (number === queryRequest) previewError.value = errorMessage(e)
  } finally {
    if (number === queryRequest) querying.value = false
  }
}
async function history(page = 1) {
  if (!detail.value) return
  const number = ++historyRequest
  historyBusy.value = true
  error.value = ''
  try {
    const value = await api.releases(detail.value.id, { pageNo: page, pageSize: 10 })
    if (number === historyRequest) {
      releases.value = value.list
      historyTotal.value = value.total
      historyPage.value = page
    }
  } catch (e) {
    if (number === historyRequest) error.value = errorMessage(e)
  } finally {
    if (number === historyRequest) historyBusy.value = false
  }
}
function openOperation(value: 'publish' | 'restore' | 'status', version = 0) {
  operation.value = value
  versionNo.value = version
  reason.value = ''
  operationError.value = ''
}
async function execute() {
  if (!detail.value || busy.value || !operation.value) return
  if (!reason.value.trim()) {
    operationError.value = '请填写操作原因'
    return
  }
  busy.value = true
  operationError.value = ''
  const revision = { id: detail.value.id, expectedRevision: detail.value.revision, reason: reason.value.trim() }
  try {
    if (operation.value === 'publish') {
      // 网络重试复用请求标识；只有草稿修订变化后才开始新的发布请求。
      if (publishRequest.revision !== revision.expectedRevision)
        publishRequest = { revision: revision.expectedRevision, id: crypto.randomUUID() }
      await api.publish({ ...revision, requestId: publishRequest.id })
      apply(await api.get(revision.id))
    } else if (operation.value === 'restore') apply(await api.restore({ ...revision, versionNo: versionNo.value }))
    else apply(await api.status({ ...revision, status: detail.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }))
    operation.value = undefined
    message.success('操作已完成')
    if (tab.value === 'history') await history()
  } catch (e) {
    operationError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function confirmLeave(): boolean | Promise<boolean> {
  if (busy.value) return false
  if (!dirty.value) return true
  return new Promise(resolve =>
    Modal.confirm({
      title: '离开并放弃未保存修改？',
      content: '已保存的草稿和发布版本会保留。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  )
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || busy.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => (to.query.id !== from.query.id ? confirmLeave() : true))
window.addEventListener('beforeunload', beforeUnload)
watch(() => route.query.id, load, { immediate: true })
watch(
  () => form.value?.source,
  source => {
    if (source && form.value && !form.value.analysis) form.value.analysis = newDatasetAnalysis()
  }
)
watch(
  [dimensions, buckets, filters],
  () => {
    queryRequest++
    querying.value = false
    result.value = undefined
    previewError.value = ''
  },
  { deep: true }
)
watch(tab, value => {
  if (value === 'history') void history()
})
watch(
  form,
  () => {
    queryRequest++
    querying.value = false
    result.value = undefined
    previewError.value = ''
  },
  { deep: true }
)
onScopeDispose(() => {
  request++
  queryRequest++
  historyRequest++
  window.removeEventListener('beforeunload', beforeUnload)
})
const previewColumns = computed(() =>
  result.value
    ? [
        ...result.value.dimensionNames.map((name, index) => ({
          title: name,
          key: `dimension-${index}`,
          dataIndex: ['labels', index]
        })),
        ...result.value.metrics.map(metric => ({
          title: metric.name,
          key: metric.id,
          dataIndex: ['values', metric.id],
          align: 'right' as const,
          customRender: ({ text }: { text: string | null }) => formatReportValue(text, metric)
        }))
      ]
    : []
)
</script>
<template>
  <section class="dataset-editor">
    <header class="editor-toolbar">
      <a-space wrap>
        <a-button @click="router.push('/nocode/report-center/datasets')">返回列表</a-button>
        <strong>{{ detail?.draft.name || '数据集' }}</strong>
        <a-tag v-if="detail">{{ detail.publishedVersion ? `V${detail.publishedVersion}` : '尚未发布' }}</a-tag>
        <a-tag v-if="dirty" color="orange">未保存</a-tag>
      </a-space>
      <a-space v-if="detail" wrap>
        <a-button
          v-if="platform.hasPermission('nocode:report:manage')"
          :disabled="dirty || busy || sourceBusy"
          @click="authorization = 'resource'"
        >
          协作权限
        </a-button>
        <a-button
          v-if="platform.hasPermission('nocode:report:manage')"
          :disabled="dirty || busy"
          @click="openOperation('status')"
        >
          {{ detail.status === 'ACTIVE' ? '停用' : '启用' }}
        </a-button>
        <a-button
          v-if="platform.hasPermission('nocode:report:publish')"
          :disabled="dirty || busy || !detail.draft.source"
          @click="openOperation('publish')"
        >
          发布
        </a-button>
        <a-button
          v-if="platform.hasPermission('nocode:report:update')"
          type="primary"
          :loading="busy"
          :disabled="!dirty || sourceBusy || replacingSource"
          @click="save"
        >
          保存草稿
        </a-button>
      </a-space>
    </header>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-spin :spinning="loading">
      <template v-if="form && detail">
        <a-form layout="vertical" class="editor-meta">
          <a-row :gutter="16">
            <a-col :xs="24" :md="8">
              <a-form-item label="数据集名称" required>
                <a-input v-model:value="form.name" aria-label="数据集名称" :disabled="!editable" :maxlength="80" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="16">
              <a-form-item label="说明">
                <a-input
                  v-model:value="form.description"
                  aria-label="数据集说明"
                  :disabled="!editable"
                  :maxlength="1000"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </a-form>
        <a-tabs v-model:active-key="tab">
          <a-tab-pane key="source" tab="来源与字段">
            <a-alert
              v-if="replacingSource"
              type="info"
              message="请选择新的来源对象和字段后保存，当前已保存版本不会受影响。"
              show-icon
            />
            <a-button v-if="form.source && editable" class="source-reset" @click="resetSource">更换来源对象</a-button>
            <DatasetSourceEditor
              :key="detail.id"
              v-model="form.source"
              :analysis="form.analysis"
              :readonly="!editable"
              @catalog="catalog = $event"
              @busy="sourceBusy = $event"
            />
          </a-tab-pane>
          <a-tab-pane key="analysis" tab="指标与筛选">
            <a-empty v-if="!form.source" description="请先选择来源对象和字段" />
            <DatasetAnalysisEditor
              v-if="form.analysis"
              v-model="form.analysis"
              :source="form.source"
              :catalog="catalog"
              :readonly="!editable"
            />
          </a-tab-pane>
          <a-tab-pane key="preview" tab="数据预览">
            <a-alert
              type="info"
              show-icon
              :message="
                dirty ? '请先保存草稿，再预览数据。' : '预览使用已保存草稿和固定筛选条件，无需单独配置数据对象权限。'
              "
            />
            <a-form layout="vertical" class="preview-filters">
              <a-form-item
                label="临时筛选"
                extra="仅用于本次预览；清除后仍遵循固定条件与数据权限。日期填写 YYYY-MM-DD，日期时间填写 YYYY-MM-DD HH:mm:ss；范围可组合大于等于和小于条件。"
              >
                <a-switch
                  aria-label="临时筛选"
                  :checked="!!filters"
                  :disabled="dirty || loading"
                  @change="
                    (value: boolean) => {
                      filters = value ? emptyScope() : null
                    }
                  "
                />
              </a-form-item>
              <DataScopeEditor v-if="filters" v-model="filters" :fields="filterFields" :readonly="dirty || loading">
                <template #afterValue="{ condition, multiple }">
                  <DatasetOptionPicker
                    v-if="condition.fieldId && ['eq', 'in', 'neq'].includes(condition.operator)"
                    :dataset-id="detail.id"
                    :condition="condition"
                    :boolean-field="filterFields.find(f => f.id === condition.fieldId)?.type === 'BOOLEAN'"
                    @select="
                      (value, operator) => {
                        condition.value = value
                        condition.operator = operator
                      }
                    "
                    :multiple="multiple"
                    :filters="filters"
                    :disabled="dirty || loading"
                  />
                </template>
              </DataScopeEditor>
            </a-form>
            <div class="preview-toolbar">
              <a-select
                v-model:value="dimensions"
                aria-label="分组维度"
                mode="multiple"
                placeholder="选择分组维度（最多 2 个）"
                class="dimension-select"
                max-tag-count="responsive"
                :options="
                  (form.source?.fields || [])
                    .filter(f => f.role === 'DIMENSION')
                    .map(f => ({
                      label: f.name,
                      value: f.id,
                      disabled: dimensions.length >= 2 && !dimensions.includes(f.id)
                    }))
                "
              />
              <div v-for="field in dateDimensions" :key="field.key" class="preview-date-bucket">
                <span>{{ field.name }}</span>
                <a-select
                  :value="buckets[field.key] || 'VALUE'"
                  :aria-label="`${field.name}分组粒度`"
                  class="bucket-select"
                  :options="[
                    { label: '原值', value: 'VALUE' },
                    { label: '按日', value: 'DAY' },
                    { label: '按月', value: 'MONTH' },
                    { label: '按年', value: 'YEAR' }
                  ]"
                  @update:value="(value: ReportBucket) => (buckets[field.key] = value)"
                />
              </div>
              <a-button :disabled="dirty || busy || !form.source" :loading="querying" @click="preview">
                查询预览
              </a-button>
            </div>
            <a-alert v-if="previewError" type="error" :message="previewError" show-icon />
            <template v-if="result">
              <p>匹配 {{ result.recordCount }} 条记录，共 {{ result.totalGroups }} 组，最多显示 100 组。</p>
              <a-descriptions class="preview-totals" bordered size="small" :column="{ xs: 1, sm: 1, md: 2, lg: 3 }">
                <a-descriptions-item v-for="metric in result.metrics" :key="metric.id" :label="`${metric.name}合计`">
                  {{ formatReportValue(result.totals[metric.id], metric) }}
                </a-descriptions-item>
              </a-descriptions>
              <a-table
                :columns="previewColumns"
                :data-source="result.groups.map((group, index) => ({ ...group, key: index }))"
                :pagination="false"
                :scroll="{ x: 'max-content' }"
              />
            </template>
            <a-empty v-else-if="!querying && !previewError" description="选择维度后查询，也可不选维度查看整体统计" />
          </a-tab-pane>
          <a-tab-pane key="history" tab="发布历史">
            <a-alert type="info" message="恢复历史版本仅更新草稿，需要重新发布后才对使用者生效。" show-icon />
            <a-table
              :loading="historyBusy"
              :scroll="{ x: 'max-content' }"
              :data-source="releases"
              row-key="versionNo"
              :columns="[
                { title: '版本', dataIndex: 'versionNo', width: 80 },
                { title: '发布原因', dataIndex: 'reason', width: 280 },
                { title: '发布时间', dataIndex: 'createTime', width: 200 },
                { title: '操作', key: 'action', width: 120, fixed: 'right' }
              ]"
              :pagination="{ current: historyPage, pageSize: 10, total: historyTotal, showSizeChanger: false }"
              @change="(p: { current?: number }) => history(p.current)"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.dataIndex === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
                <a-button
                  v-else-if="column.key === 'action' && platform.hasPermission('nocode:report:update')"
                  type="link"
                  :disabled="dirty || busy"
                  @click="openOperation('restore', record.versionNo)"
                >
                  恢复到草稿
                </a-button>
              </template>
            </a-table>
          </a-tab-pane>
        </a-tabs>
      </template>
    </a-spin>
    <DatasetAuthorization v-if="authorization && detail" :dataset-id="detail.id" @close="authorization = undefined" />
    <OsModalForm
      :open="!!operation"
      :title="
        operation === 'publish'
          ? '发布数据集'
          : operation === 'restore'
            ? `恢复 V${versionNo} 到草稿`
            : detail?.status === 'ACTIVE'
              ? '停用数据集'
              : '启用数据集'
      "
      :loading="busy"
      :width="560"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      @ok="execute"
      @cancel="
        () => {
          if (!busy) operation = undefined
        }
      "
    >
      <template #formItems>
        <a-alert v-if="operationError" type="error" :message="operationError" show-icon />
        <a-form-item label="操作原因" required>
          <a-textarea v-model:value="reason" aria-label="操作原因" :rows="3" :maxlength="1000" />
        </a-form-item>
      </template>
    </OsModalForm>
  </section>
</template>
<style scoped>
.dataset-editor {
  height: 100%;
  overflow: auto;
  padding: var(--spacing-lg);
  background: var(--color-bg-container);
}
.editor-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}
.editor-toolbar > :deep(.ant-space),
.preview-toolbar {
  max-width: 100%;
}
.editor-toolbar :deep(.ant-space-item),
.preview-toolbar :deep(.ant-space-item) {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.editor-meta {
  margin-top: var(--spacing-lg);
}
.preview-toolbar,
.preview-date-bucket {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.preview-toolbar {
  margin: var(--spacing-lg) 0;
}
.preview-totals :deep(.ant-descriptions-item-content) {
  white-space: nowrap;
}
.preview-filters {
  margin-top: var(--spacing-lg);
}
.dimension-select {
  width: calc(var(--spacing-lg) * 19);
  max-width: 100%;
}
.bucket-select {
  min-width: calc(var(--spacing-lg) * 7);
}
.source-reset {
  margin-bottom: var(--spacing-md);
}
</style>
