<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { DownOutlined, ReloadOutlined, RightOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskDate, taskStates } from '@/nocode/task-center'
import { orderTaskSiblings } from '@/nocode/task-arrangement'
import type { TaskRow, TaskState, TaskTemplateInstance, TaskTemplateVersionSummary } from '@/types/nocode/task-center'

const props = defineProps<{
  templateId: string
  publishedVersion?: number | null
  versionSummaries?: TaskTemplateVersionSummary[]
}>()
const emit = defineEmits<{ open: [id: string] }>()
const api = useNocodePlatform().taskCenter
const error = ref('')
const expanded = ref<string[]>([])
const table = useOsTablePage<TaskTemplateInstance, { search: string; version: number | ''; status: TaskState | '' }>({
  defaultQuery: () => ({ search: '', version: '', status: '' }),
  fetchFn: async query => {
    expanded.value = []
    error.value = ''
    if (!props.templateId) return { list: [], total: 0 }
    return api.templateInstances({
      templateId: props.templateId,
      pageNo: query.pageNum,
      pageSize: query.pageSize,
      ...(query.search?.trim() ? { search: query.search.trim() } : {}),
      ...(query.version ? { version: query.version } : {}),
      ...(query.status ? { status: query.status } : {})
    })
  },
  queryMode: 'submitted',
  clearDataOnError: true,
  correctOutOfRange: true,
  onError: cause => (error.value = errorMessage(cause))
})
const { queryForm, tableData, loading, pagination } = table
// 模板创建任务后刷新已打开的实例页签，沿用已提交条件和当前页码。
defineExpose({ refresh: table.fetchData })
watch(
  () => props.templateId,
  () => table.handleReset()
)
const versions = computed(() => [
  { value: '', label: '全部版本' },
  ...(props.versionSummaries || []).map(item => ({
    value: item.version,
    label: `V${item.version}${item.primary ? ' · 主版本' : ''}`
  }))
])
interface DisplayRow {
  key: string
  instance: TaskTemplateInstance
  task: TaskRow | null
  depth: number
}
const rows = computed<DisplayRow[]>(() =>
  tableData.value.flatMap(instance => {
    const result: DisplayRow[] = [{ key: instance.rootId, instance, task: instance.root, depth: 0 }]
    if (!expanded.value.includes(instance.rootId)) return result
    const nodes = orderTaskSiblings(instance.nodes)
    const ids = new Set(nodes.map(node => node.id))
    const seen = new Set<string>()
    const visit = (task: TaskRow, depth: number) => {
      if (seen.has(task.id)) return
      seen.add(task.id)
      result.push({ key: task.id, instance, task, depth })
      nodes.filter(node => node.parentId === task.id).forEach(node => visit(node, depth + 1))
    }
    nodes.filter(node => !node.parentId || !ids.has(node.parentId)).forEach(node => visit(node, 1))
    return result
  })
)
const columns = [
  { key: 'title', title: '任务 / 子任务', width: 300, align: 'left' as const },
  { key: 'version', title: '来源版本', width: 100 },
  { key: 'assignee', title: '负责人', width: 140 },
  { key: 'status', title: '状态', width: 120 },
  { key: 'expectedEnd', title: '预计结束', width: 170 },
  { key: 'createdAt', title: '创建时间', width: 170 },
  { key: 'actions', title: '操作', width: 100, fixed: 'right' as const }
]
function toggle(id: string) {
  expanded.value = expanded.value.includes(id) ? expanded.value.filter(item => item !== id) : [...expanded.value, id]
}
function statusLabel(record: DisplayRow) {
  const status = record.depth === 0 ? record.instance.status : record.task?.status
  return status ? taskStates[status] : '—'
}
</script>
<template>
  <div class="task-template-instances nocode-embedded-table">
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <OsTablePage
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      server-pagination
      :show-index="false"
      row-key="key"
      :scroll="{ x: 1100 }"
      resizable
      show-column-settings
      column-settings-key="task-template-instances"
      @change="table.handleTableChange"
      @search="table.handleQuery"
    >
      <template #search>
        <a-form layout="inline">
          <a-form-item label="任务名称">
            <a-input
              v-model:value="queryForm.search"
              allow-clear
              placeholder="搜索总任务或可见子任务"
              style="width: 220px"
              @press-enter="table.handleQuery"
            />
          </a-form-item>
          <a-form-item label="来源版本">
            <a-select v-model:value="queryForm.version" :options="versions" style="width: 120px" />
          </a-form-item>
          <a-form-item label="总任务状态">
            <a-select
              v-model:value="queryForm.status"
              :options="[
                { value: '', label: '全部' },
                ...Object.entries(taskStates).map(([value, label]) => ({ value, label }))
              ]"
              style="width: 130px"
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="table.handleQuery">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="table.handleReset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template #title>任务实例</template>
      <template #actions><a-button @click="table.fetchData">刷新</a-button></template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'title'">
          <div class="task-template-instances__title" :style="{ paddingLeft: `${Math.min(record.depth, 6) * 20}px` }">
            <a-button
              v-if="record.depth === 0 && record.instance.nodes.length"
              type="text"
              :aria-label="expanded.includes(record.key) ? '收起子任务' : '展开子任务'"
              @click="toggle(record.key)"
            >
              <DownOutlined v-if="expanded.includes(record.key)" />
              <RightOutlined v-else />
            </a-button>
            <span v-else class="task-template-instances__spacer">{{ record.depth ? '·' : '' }}</span>
            <div>
              <a-button v-if="record.task" type="link" @click="emit('open', record.task.id)">
                {{ record.task.title }}
              </a-button>
              <strong v-else>{{ record.instance.title }}</strong>
              <div v-if="record.depth === 0 && !record.task" class="task-template-instances__hint">
                仅可查看有权限的子任务
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="column.key === 'version'">
          {{ record.depth === 0 ? `V${record.instance.templateVersion ?? '—'}` : '—' }}
        </template>
        <template v-else-if="column.key === 'assignee'">{{ record.task?.assigneeName || '—' }}</template>
        <template v-else-if="column.key === 'status'">{{ statusLabel(record) }}</template>
        <template v-else-if="column.key === 'expectedEnd'">{{ taskDate(record.task?.expectedEnd) }}</template>
        <template v-else-if="column.key === 'createdAt'">{{ taskDate(record.task?.createdAt) }}</template>
        <template v-else-if="column.key === 'actions'">
          <a-button v-if="record.task" type="link" @click="emit('open', record.task.id)">详情</a-button>
          <span v-else>—</span>
        </template>
      </template>
      <template #empty>
        <a-empty :description="error ? '加载失败，请刷新重试' : '暂无符合条件且有权限查看的任务实例'" />
      </template>
    </OsTablePage>
  </div>
</template>
<style scoped>
.task-template-instances {
  min-width: 0;
}
.task-template-instances__title {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 4px;
}
.task-template-instances__title > .ant-btn,
.task-template-instances__spacer {
  flex: 0 0 24px;
  width: 24px;
  text-align: center;
}
.task-template-instances__title .ant-btn-link {
  padding-inline: 0;
  white-space: normal;
  text-align: left;
  height: auto;
}
.task-template-instances__hint {
  color: var(--text-color-secondary, #86909c);
  font-size: 12px;
}
</style>
