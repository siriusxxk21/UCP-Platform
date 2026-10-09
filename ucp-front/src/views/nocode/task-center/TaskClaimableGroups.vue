<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskAssignmentLabel, taskDate, taskPriorities, taskStateColors, taskStates } from '@/nocode/task-center'
import type { TaskClaimableGroup, TaskClaimableItem, TaskPriority } from '@/types/nocode/task-center'
import TaskClaimDialog from './TaskClaimDialog.vue'
import TaskHierarchyCell from './TaskHierarchyCell.vue'
import TaskScheduleNotice from './TaskScheduleNotice.vue'

const emit = defineEmits<{
  detail: [id: string]
  structure: [anchorTaskId: string, selectedId: string]
  changed: []
}>()
const api = useNocodePlatform().taskCenter
const expanded = ref<string[]>([])
const collapsedNodes = ref<string[]>([])
const items = ref<Record<string, TaskClaimableItem[]>>({})
const busyRoots = ref<string[]>([])
const error = ref('')
const claimTarget = ref<{ id: string; rootId: string; title: string; revision?: number }>()
let generation = 0
onBeforeUnmount(() => generation++)
const table = useOsTablePage<TaskClaimableGroup, { search: string; priority: TaskPriority | '' }>({
  defaultQuery: () => ({ search: '', priority: '' }),
  fetchFn: async query => {
    generation++
    expanded.value = []
    collapsedNodes.value = []
    items.value = {}
    busyRoots.value = []
    error.value = ''
    return api.claimableGroups({
      pageNo: query.pageNum,
      pageSize: query.pageSize,
      ...(query.search?.trim() ? { search: query.search.trim() } : {}),
      ...(query.priority ? { priority: query.priority } : {})
    })
  },
  onError: cause => {
    error.value = errorMessage(cause)
  },
  clearDataOnError: true,
  correctOutOfRange: true,
  queryMode: 'submitted'
})
const { queryForm, tableData, loading, pagination } = table
interface Row {
  key: string
  root: TaskClaimableGroup
  group?: TaskClaimableGroup
  item?: TaskClaimableItem
  depth: number
}
const rows = computed<Row[]>(() =>
  tableData.value.flatMap(group => {
    const result: Row[] = [{ key: `group:${group.rootId}`, root: group, group, depth: 0 }]
    if (!expanded.value.includes(group.rootId)) return result
    const children = (items.value[group.rootId] || []).filter(item => item.id !== group.rootId)
    const ids = new Set(children.map(item => item.id))
    const seen = new Set<string>()
    const visit = (item: TaskClaimableItem, depth: number, visible = true) => {
      if (seen.has(item.id)) return
      seen.add(item.id)
      if (visible) result.push({ key: `item:${item.id}`, root: group, item, depth })
      children
        .filter(child => child.parentId === item.id)
        .forEach(child => visit(child, depth + 1, visible && !collapsedNodes.value.includes(item.id)))
    }
    children.filter(item => !item.parentId || !ids.has(item.parentId)).forEach(item => visit(item, 1))
    // 兼容不完整的旧摘要，避免异常父链让仍可领取的节点从目录中消失。
    children.filter(item => !seen.has(item.id)).forEach(item => visit(item, 1))
    return result
  })
)
const columns = [
  { key: 'title', title: '任务 / 子任务', width: 320, align: 'left' as const },
  { key: 'progress', title: '进度', width: 100 },
  { key: 'status', title: '状态', width: 105 },
  { key: 'expectedStart', title: '预计开始', width: 125 },
  { key: 'expectedEnd', title: '预计完成', width: 125 },
  { key: 'assignee', title: '负责人', width: 150 },
  { key: 'priority', title: '优先级', width: 85 },
  { key: 'actions', title: '操作', width: 205, fixed: 'right' as const, align: 'left' as const }
]
const scrollWidth = columns.reduce((total, column) => total + column.width, 0)
const canClaimWhole = (group: TaskClaimableGroup) =>
  group.wholeClaimCount == null ? group.canClaimGroup : group.wholeClaimCount > 0
const canInspectGroup = (group: TaskClaimableGroup) => group.rootVisible || canClaimWhole(group)
const canClaimRemaining = (group: TaskClaimableGroup) =>
  group.ownership === 'MINE' && (group.remainingClaimCount || 0) > 0
const claimableChildren = (group: TaskClaimableGroup) =>
  Math.max(0, group.claimableChildCount ?? group.claimableCount - (canClaimWhole(group) ? 1 : 0))
const rowId = (row: Row) => row.item?.id || row.root.rootId
const rowTitle = (row: Row) => row.item?.title || row.root.title
const rowSummary = (row: Row) => row.item || row.root
const matched = (row: Row) => (row.item ? row.item.canClaim : canClaimWhole(row.root) || canClaimRemaining(row.root))
const childCount = (row: Row) =>
  rowSummary(row).childCount ??
  (row.item
    ? (items.value[row.root.rootId] || []).filter(item => item.parentId === row.item?.id).length
    : claimableChildren(row.root))
const hasChildren = (row: Row) => childCount(row) > 0
const isExpanded = (row: Row) =>
  row.item ? !collapsedNodes.value.includes(row.item.id) : expanded.value.includes(row.root.rootId)
const hierarchy = (row: Row) => ({
  depth: row.depth,
  outline: '',
  label: row.item ? '子任务' : '总任务',
  parentTitle: '',
  missingParent: false,
  childCount: childCount(row)
})
const hasDetail = (row: Row) =>
  row.item ? row.item.detailVisible === true || row.item.canClaim : canInspectGroup(row.root)
const anchorTaskId = (row: Row) => row.item?.anchorTaskId || row.root.anchorTaskId
const canInspect = (row: Row) => hasDetail(row) || !!anchorTaskId(row)
function inspect(row: Row) {
  if (hasDetail(row)) emit('detail', rowId(row))
  else {
    const anchor = anchorTaskId(row)
    if (anchor) emit('structure', anchor, rowId(row))
  }
}
function assignee(row: Row) {
  if (row.item) return row.item.assigneeName || taskAssignmentLabel(row.item)
  if (row.root.ownerName) return row.root.ownerName
  if (row.root.ownership === 'MINE') return '我'
  if (row.root.ownership === 'UNCLAIMED' || canClaimWhole(row.root)) return '待领取'
  if (row.root.ownership === 'ASSIGNED') return '已分配'
  return '—'
}
function progress(row: Row) {
  const summary = rowSummary(row)
  return summary.childCount && summary.completedChildCount != null
    ? { total: summary.childCount, completed: summary.completedChildCount }
    : null
}
async function toggle(row: Row) {
  if (row.item) {
    if (collapsedNodes.value.includes(row.item.id)) {
      const descendants = new Set([row.item.id])
      const pending = [row.item.id]
      const nodes = items.value[row.root.rootId] || []
      while (pending.length) {
        const parentId = pending.pop()
        if (parentId == null) break
        for (const child of nodes) {
          if (child.parentId !== parentId || descendants.has(child.id)) continue
          descendants.add(child.id)
          pending.push(child.id)
        }
      }
      collapsedNodes.value = collapsedNodes.value.filter(id => !descendants.has(id))
    } else collapsedNodes.value = [...collapsedNodes.value, row.item.id]
    return
  }
  const group = row.root
  if (busyRoots.value.includes(group.rootId)) return
  if (expanded.value.includes(group.rootId)) {
    expanded.value = expanded.value.filter(id => id !== group.rootId)
    return
  }
  const current = generation
  busyRoots.value.push(group.rootId)
  error.value = ''
  try {
    const result = await api.claimableChildren(group.rootId)
    if (current !== generation) return
    items.value[group.rootId] = result
    const ids = new Set(result.map(item => item.id))
    collapsedNodes.value = collapsedNodes.value.filter(id => !ids.has(id))
    expanded.value.push(group.rootId)
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  } finally {
    if (current === generation) busyRoots.value = busyRoots.value.filter(id => id !== group.rootId)
  }
}
let refreshing: Promise<void> | undefined
let refreshAgain = false
function refresh(): Promise<void> {
  if (refreshing) {
    refreshAgain = true
    return refreshing
  }
  refreshing = (async () => {
    do {
      refreshAgain = false
      const restore = [...expanded.value]
      const collapsed = [...collapsedNodes.value]
      const fetched = table.fetchData()
      const current = generation
      await fetched
      if (current !== generation) break
      for (const id of restore) {
        if (current !== generation) break
        const group = tableData.value.find(item => item.rootId === id)
        if (group) await toggle({ key: `group:${id}`, root: group, group, depth: 0 })
      }
      if (current === generation) collapsedNodes.value = collapsed
    } while (refreshAgain)
  })().finally(() => {
    refreshing = undefined
  })
  return refreshing
}
defineExpose({ refresh })
function saved() {
  emit('changed')
  void refresh()
}
</script>
<template>
  <div class="task-claimable-groups">
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <OsTablePage
      :columns="columns"
      :data-source="rows"
      row-key="key"
      :pagination="pagination"
      server-pagination
      :show-index="false"
      :loading="loading"
      :scroll="{ x: scrollWidth }"
      fixed-layout
      resizable
      show-column-settings
      column-settings-key="task-claimable-groups"
      @change="table.handleTableChange"
      @search="table.handleQuery"
    >
      <template #title>可领取任务</template>
      <template #search>
        <a-form layout="inline">
          <a-form-item label="名称">
            <a-input
              v-model:value="queryForm.search"
              allow-clear
              placeholder="搜索任务名称"
              @press-enter="table.handleQuery"
            />
          </a-form-item>
          <a-form-item label="优先级">
            <a-select
              v-model:value="queryForm.priority"
              style="width: 110px"
              :options="[
                { value: '', label: '全部' },
                ...Object.entries(taskPriorities).map(([value, label]) => ({ value, label }))
              ]"
            />
          </a-form-item>
          <a-button type="primary" @click="table.handleQuery">
            <SearchOutlined />
            查询
          </a-button>
          <a-button @click="table.handleReset">
            <ReloadOutlined />
            重置
          </a-button>
        </a-form>
      </template>
      <template #actions><a-button :loading="loading" @click="refresh">刷新</a-button></template>
      <template #bodyCell="{ column, record }">
        <TaskHierarchyCell
          v-if="column.key === 'title'"
          class="task-claimable-groups__hierarchy"
          :class="{
            'task-claimable-groups__matched': matched(record),
            'task-claimable-groups__context': !matched(record)
          }"
          :item="hierarchy(record)"
          compact
          :expandable="hasChildren(record)"
          :expanded="isExpanded(record)"
          :loading="!record.item && busyRoots.includes(record.root.rootId)"
          :toggle-label="`${isExpanded(record) ? '收起' : '展开'}子任务：${rowTitle(record)}`"
          @toggle="toggle(record)"
        >
          <a-button v-if="canInspect(record)" type="link" :title="rowTitle(record)" @click="inspect(record)">
            {{ rowTitle(record) }}
          </a-button>
          <span v-else :title="rowTitle(record)">{{ rowTitle(record) }}</span>
        </TaskHierarchyCell>
        <template v-else-if="column.key === 'progress'">
          <div
            v-if="progress(record)"
            class="task-claimable-groups__progress"
            :title="`下级任务已完成 ${progress(record)!.completed}/${progress(record)!.total}`"
          >
            <span>{{ progress(record)!.completed }}/{{ progress(record)!.total }}</span>
            <span
              class="task-claimable-groups__progress-track"
              role="progressbar"
              aria-label="下级任务完成进度"
              :aria-valuemin="0"
              :aria-valuemax="progress(record)!.total"
              :aria-valuenow="progress(record)!.completed"
            >
              <span
                :style="{ width: `${Math.min(100, (progress(record)!.completed / progress(record)!.total) * 100)}%` }"
              />
            </span>
          </div>
          <span v-else class="task-claimable-groups__muted">—</span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag v-if="rowSummary(record).status" :color="taskStateColors[rowSummary(record).status!]">
            {{ taskStates[rowSummary(record).status!] }}
          </a-tag>
          <span v-else class="task-claimable-groups__muted">—</span>
        </template>
        <template v-else-if="column.key === 'expectedStart' || column.key === 'expectedEnd'">
          {{
            taskDate(column.key === 'expectedStart' ? rowSummary(record).expectedStart : rowSummary(record).expectedEnd)
          }}
          <TaskScheduleNotice v-if="column.key === 'expectedEnd'" :summary="rowSummary(record).scheduleSummary" />
        </template>
        <template v-else-if="column.key === 'assignee'">{{ assignee(record) }}</template>
        <template v-else-if="column.key === 'priority'">
          {{ rowSummary(record).priority ? taskPriorities[rowSummary(record).priority!] : '—' }}
        </template>
        <div v-else-if="column.key === 'actions'" class="task-claimable-groups__actions">
          <template v-if="record.group">
            <a-button
              v-if="canClaimRemaining(record.group)"
              type="link"
              @click="claimTarget = { id: record.group.rootId, rootId: record.group.rootId, title: record.group.title }"
            >
              领取剩余{{ record.group.remainingClaimCount }}项
            </a-button>
            <a-button
              v-else-if="canClaimWhole(record.group)"
              type="link"
              @click="claimTarget = { id: record.group.rootId, rootId: record.group.rootId, title: record.group.title }"
            >
              {{ claimableChildren(record.group) > 0 ? '领取整个任务' : '领取任务' }}
            </a-button>
            <a-button
              v-else-if="hasChildren(record)"
              type="link"
              :loading="busyRoots.includes(record.group.rootId)"
              @click="!isExpanded(record) && toggle(record)"
            >
              选择子任务
            </a-button>
          </template>
          <a-button v-else-if="record.item?.canClaim" type="link" @click="claimTarget = record.item">
            只领这一项
          </a-button>
          <a-button v-if="canInspect(record)" type="link" @click="inspect(record)">
            {{ hasDetail(record) ? '详情' : '查看编排' }}
          </a-button>
          <span v-else-if="record.item && !record.item.canClaim" class="task-claimable-groups__muted">仅概要</span>
        </div>
      </template>
      <template #empty>
        <a-empty :description="error ? '领取目录加载失败，请刷新重试' : '暂时没有可领取的任务'" />
      </template>
    </OsTablePage>
    <TaskClaimDialog
      v-if="claimTarget"
      :key="claimTarget.id"
      :task="claimTarget"
      @close="claimTarget = undefined"
      @saved="saved"
    />
  </div>
</template>
<style scoped>
.task-claimable-groups__hierarchy :deep(.task-hierarchy__node) {
  padding-block: 0;
  min-height: var(--control-height);
}
.task-claimable-groups__hierarchy :deep(.task-hierarchy__title) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-claimable-groups__hierarchy :deep(.task-hierarchy__title .ant-btn-link) {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-claimable-groups__context :deep(.task-hierarchy__node) {
  background: var(--neutral-bg);
  border-radius: var(--radius-sm);
}
.task-claimable-groups__context :deep(.task-hierarchy__title .ant-btn-link),
.task-claimable-groups__context :deep(.task-hierarchy__title) {
  color: var(--text-secondary);
  font-weight: 400;
}
.task-claimable-groups__matched :deep(.task-hierarchy__node) {
  background: var(--brand-light);
}
.task-claimable-groups__matched :deep(.task-hierarchy__title) {
  color: var(--brand);
}
.task-claimable-groups__progress {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.task-claimable-groups__progress-track {
  width: 40px;
  height: var(--spacing-xs);
  overflow: hidden;
  background: var(--border);
  border-radius: var(--radius-sm);
}
.task-claimable-groups__progress-track > span {
  display: block;
  height: 100%;
  background: var(--brand);
}
.task-claimable-groups__muted {
  color: var(--text-secondary);
}
.task-claimable-groups__actions {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  white-space: nowrap;
}
.task-claimable-groups__actions :deep(.ant-btn-link) {
  height: auto;
  padding-inline: 0;
}
.task-claimable-groups__actions :deep(.ant-btn-link + .ant-btn-link) {
  border-left: 1px solid var(--border);
  border-radius: 0;
  padding-left: var(--spacing-sm);
}
</style>
