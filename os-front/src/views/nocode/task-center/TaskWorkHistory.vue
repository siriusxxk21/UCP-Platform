<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskTime } from '@/nocode/task-center'
import { changedKeys, historyValue } from '@/nocode/record-history'
import HistoryDetailChanges from '@/views/nocode/record-history/HistoryDetailChanges.vue'
import type { TaskRow } from '@/types/nocode/task-center'
import type {
  TaskWorkEntry,
  TaskWorkHistoryRow,
  TaskWorkHistoryDetail,
  TaskWorkOperation
} from '@/types/nocode/task-work-entries'

const props = defineProps<{
  task: TaskRow
  entries: TaskWorkEntry[]
  initialEntryKey?: string
  initialRecordId?: string
  employeeView?: boolean
}>()
const emit = defineEmits<{ close: [] }>()
const api = useNocodePlatform().taskCenter
const drawerWidth = ref(Math.min(1180, window.innerWidth))
function resizeDrawer() {
  drawerWidth.value = Math.min(1180, window.innerWidth)
}
window.addEventListener('resize', resizeDrawer)
const error = ref(''),
  detailError = ref(''),
  detailLoading = ref(false)
const selected = ref<TaskWorkHistoryRow>(),
  detail = ref<TaskWorkHistoryDetail>()
const recordId = ref(props.initialRecordId || '')
const operations: Record<TaskWorkOperation, string> = {
  CREATED: '新增',
  UPDATED: '修改',
  DELETED: '删除',
  LINKED: '关联'
}
const columns = [
  { key: 'time', title: '时间', width: 170 },
  { key: 'taskTitle', title: '任务节点', width: 160 },
  { key: 'actorName', title: '操作人', width: 100 },
  { key: 'entryName', title: '业务关联项', width: 150 },
  { key: 'recordLabel', title: '业务记录', width: 170 },
  { key: 'operation', title: '操作类型', width: 90 },
  { key: 'actions', title: '详情', width: 110, fixed: 'right' as const }
]
const { tableData, pagination, loading, queryForm, handleQuery, handleTableChange, fetchData } = useOsTablePage({
  defaultQuery: () => ({
    entryKey: props.initialEntryKey || '',
    onlyCurrentTask: !!props.employeeView || !!props.task.parentId,
    operation: '' as TaskWorkOperation | '',
    search: ''
  }),
  immediate: false,
  queryMode: 'submitted',
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: params => {
    error.value = ''
    return api.entryHistoryPage({
      taskId: props.task.id,
      entryKey: params.entryKey || null,
      onlyCurrentTask: params.onlyCurrentTask,
      recordId: recordId.value || null,
      operation: params.operation || null,
      search: params.search || null,
      pageNo: params.pageNum,
      pageSize: params.pageSize
    })
  },
  onError: cause => {
    error.value = errorMessage(cause)
  }
})
const entryOptions = computed(() => [
  { value: '', label: '全部业务关联项' },
  ...props.entries.map(entry => ({
    value: entry.config.key,
    label: entry.config.name
  }))
])
const changedFields = computed(() => {
  if (!detail.value || !detail.value.beforeKnown || !detail.value.afterKnown) return []
  const changed = new Set(changedKeys(detail.value.before, detail.value.after))
  return detail.value.fields.filter(field => changed.has(field.id))
})
const snapshots = computed(() =>
  !detail.value
    ? []
    : [
        {
          key: 'before',
          title: detail.value.row.operation === 'DELETED' ? '删除前数据' : '操作前数据',
          values: detail.value.before
        },
        {
          key: 'after',
          title: detail.value.row.operation === 'LINKED' ? '关联时数据' : '操作后数据',
          values: detail.value.after
        }
      ].filter(item => item.values !== null)
)
let generation = 0
function back() {
  generation++
  selected.value = undefined
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = false
}
async function openDetail(row: TaskWorkHistoryRow) {
  const token = ++generation
  selected.value = row
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = true
  try {
    const result = await api.entryHistoryDetail({
      taskId: props.task.id,
      entryKey: row.entryKey,
      contributionId: row.id
    })
    if (token === generation) detail.value = result
  } catch (cause) {
    if (token === generation) detailError.value = errorMessage(cause)
  } finally {
    if (token === generation) detailLoading.value = false
  }
}
function clearRecord() {
  recordId.value = ''
  handleQuery()
}
watch(
  () => [props.task.id, props.task.revision],
  () => {
    back()
    tableData.value = []
    handleQuery()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
  window.removeEventListener('resize', resizeDrawer)
})
</script>

<template>
  <OsModalForm
    open
    title="数据操作记录"
    root-class-name="task-work-history-drawer"
    :width="drawerWidth"
    :resizable="false"
    :show-footer="false"
    :wrap-form="false"
    display-mode="drawer"
    :allow-switch-display="false"
    @cancel="emit('close')"
  >
    <template #formItems>
      <div class="task-work-history">
        <template v-if="selected">
          <a-space wrap>
            <a-button @click="back">返回数据操作记录</a-button>
            <strong>{{ operations[selected.operation] }} · {{ selected.recordLabel || selected.recordId }}</strong>
            <a-tag>历史快照 · 只读</a-tag>
          </a-space>
          <a-typography-text type="secondary">
            {{ selected.taskTitle }} · {{ selected.actorName || '人员信息未留存' }} · {{ taskTime(selected.time) }}
          </a-typography-text>
          <a-spin v-if="detailLoading" tip="加载当时数据…" />
          <a-alert v-else-if="detailError" type="error" show-icon :message="detailError">
            <template #action><a-button size="small" @click="openDetail(selected)">重试</a-button></template>
          </a-alert>
          <template v-else-if="detail">
            <a-alert
              v-if="detail.relatedUpdate"
              type="info"
              show-icon
              message="本次操作还更新了关联记录；下方展示当前表单有权限查看的字段和明细。"
            />
            <a-alert
              v-if="detail.row.operation === 'LINKED'"
              type="info"
              show-icon
              message="此次操作将记录关联到任务，没有修改业务数据。下方展示关联时的数据。"
            />
            <a-alert
              v-else-if="!detail.row.historyKnown || !detail.beforeKnown || !detail.afterKnown"
              type="info"
              show-icon
              message="这条历史记录未完整保留操作前后数据，仅展示已留存的快照，无法准确还原字段变化。"
            />
            <template v-else>
              <h4>本次字段变化</h4>
              <div v-if="changedFields.length" class="task-history-diff" role="table" aria-label="本次字段变化">
                <strong role="columnheader">字段</strong>
                <strong role="columnheader">操作前</strong>
                <strong role="columnheader">操作后</strong>
                <template v-for="field in changedFields" :key="field.id">
                  <strong role="cell">{{ field.name }}</strong>
                  <span role="cell">{{ detail.before ? historyValue(detail.before[field.id]) : '记录尚未创建' }}</span>
                  <span role="cell">{{ detail.after ? historyValue(detail.after[field.id]) : '记录已删除' }}</span>
                </template>
              </div>
              <a-typography-text v-else type="secondary">当前可查看的主表字段没有变化。</a-typography-text>
            </template>
            <HistoryDetailChanges
              v-if="
                detail.row.operation !== 'LINKED' && detail.row.historyKnown && detail.beforeKnown && detail.afterKnown
              "
              :groups="detail.details || []"
            />
            <a-collapse v-if="snapshots.length">
              <a-collapse-panel v-for="snapshot in snapshots" :key="snapshot.key" :header="snapshot.title">
                <dl class="task-history-snapshot">
                  <template v-for="field in detail.fields" :key="field.id">
                    <dt>{{ field.name }}</dt>
                    <dd>{{ historyValue(snapshot.values?.[field.id]) }}</dd>
                  </template>
                </dl>
                <section v-for="group in detail.details || []" :key="group.id">
                  <h4>{{ group.name }}</h4>
                  <article v-for="(values, id) in snapshot.key === 'before' ? group.before : group.after" :key="id">
                    <a-typography-text type="secondary">明细记录 {{ id }}</a-typography-text>
                    <dl class="task-history-snapshot">
                      <template v-for="field in group.fields" :key="field.id">
                        <dt>{{ field.name }}</dt>
                        <dd>{{ historyValue(values[field.id]) }}</dd>
                      </template>
                    </dl>
                  </article>
                </section>
              </a-collapse-panel>
            </a-collapse>
          </template>
        </template>
        <template v-else>
          <a-alert v-if="error" type="error" show-icon :message="error" />
          <a-alert v-if="recordId" type="info" message="当前仅查看这条数据的操作记录">
            <template #action><a-button size="small" @click="clearRecord">查看全部记录</a-button></template>
          </a-alert>
          <OsTablePage
            class="task-history-table"
            :columns="columns"
            :data-source="tableData"
            :loading="loading"
            :pagination="pagination"
            row-key="id"
            :show-advanced-search="false"
            :show-batch-bar="false"
            :scroll="{ x: 'max-content' }"
            column-settings-key="task-work-history"
            @change="handleTableChange"
            @refresh="fetchData"
          >
            <template #search>
              <a-form layout="inline" @submit.prevent>
                <a-form-item label="业务关联项">
                  <a-select
                    v-model:value="queryForm.entryKey"
                    class="task-history-control"
                    aria-label="数据操作记录表单"
                    :options="entryOptions"
                    @change="handleQuery"
                  />
                </a-form-item>
                <a-form-item label="操作类型">
                  <a-select
                    v-model:value="queryForm.operation"
                    class="task-history-operation"
                    aria-label="数据操作记录类型"
                    :options="[
                      { value: '', label: '全部操作' },
                      ...Object.entries(operations).map(([value, label]) => ({ value, label }))
                    ]"
                    @change="handleQuery"
                  />
                </a-form-item>
                <a-form-item label="节点范围">
                  <a-radio-group
                    v-model:value="queryForm.onlyCurrentTask"
                    aria-label="数据操作记录节点范围"
                    @change="handleQuery"
                  >
                    <a-radio-button :value="true">本节点</a-radio-button>
                    <a-radio-button :value="false">本节点及下级</a-radio-button>
                  </a-radio-group>
                </a-form-item>
                <a-form-item label="关键词">
                  <a-input-search
                    v-model:value="queryForm.search"
                    class="task-history-control"
                    placeholder="搜索任务、人员或记录"
                    allow-clear
                    @search="handleQuery"
                  />
                </a-form-item>
              </a-form>
            </template>
            <template #title>{{ task.title }} · 数据操作记录</template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'time'">{{ taskTime(record.time) }}</template>
              <template v-else-if="column.key === 'operation'">
                <a-tag
                  :color="record.operation === 'DELETED' ? 'red' : record.operation === 'CREATED' ? 'green' : 'blue'"
                >
                  {{ operations[record.operation as TaskWorkOperation] }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'entryName'">
                {{ record.entryName }}
              </template>
              <template v-else-if="column.key === 'actions'">
                <a v-if="record.detailAvailable" @click="openDetail(record)">查看当时数据</a>
                <span v-else>仅有操作记录</span>
              </template>
              <template v-else-if="column.key !== '_index'">{{ record[column.key] || '—' }}</template>
            </template>
          </OsTablePage>
        </template>
      </div>
    </template>
  </OsModalForm>
</template>

<style scoped>
:global(.task-work-history-drawer > .ant-drawer-content-wrapper) {
  max-width: 100vw;
}
.task-work-history {
  display: grid;
  gap: var(--spacing-md);
  min-width: 0;
}
.task-work-history h4 {
  margin: 0;
}
.task-history-table {
  height: auto;
  min-width: 0;
}
.task-history-control {
  width: calc(var(--spacing-lg) * 11);
  max-width: 100%;
}
.task-history-operation {
  width: calc(var(--spacing-lg) * 6);
}
.task-history-category {
  color: var(--text-secondary);
  font-size: var(--font-size-sm);
}
.task-history-diff {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) minmax(0, 2fr);
  gap: var(--spacing-sm);
}
.task-history-diff > * {
  padding: var(--spacing-sm);
  border-bottom: 1px solid var(--border-color);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.task-history-snapshot {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 3fr);
  gap: var(--spacing-sm);
}
.task-history-snapshot dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
