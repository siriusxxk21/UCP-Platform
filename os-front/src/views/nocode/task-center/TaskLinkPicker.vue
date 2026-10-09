<script setup lang="ts">
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import { v4 as uuid } from 'uuid'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskStates } from '@/nocode/task-center'
import { taskHierarchy } from '@/nocode/task-hierarchy'
import { useOsTablePage } from '@/composables/useOsTablePage'
import type { TaskPageContext, TaskRow } from '@/types/nocode/task-center'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import TaskHierarchyCell from './TaskHierarchyCell.vue'

const props = defineProps<{ context: TaskPageContext & { recordId: string } }>()
const emit = defineEmits<{ close: []; linked: [] }>()
const api = useNocodePlatform().taskCenter
const error = ref(''),
  busyId = ref('')
const requestKeys = new Map<string, string>()
const table = useOsTablePage<TaskRow, { search: string }>({
  defaultQuery: () => ({ search: '' }),
  fetchFn: params =>
    api.recordLinkCandidates(props.context, {
      scope: 'MANAGE',
      tab: 'ALL',
      date: dayjs().format('YYYY-MM-DD'),
      search: params.search,
      pageNo: params.pageNum,
      pageSize: params.pageSize
    }),
  onError: e => {
    error.value = errorMessage(e)
  },
  clearDataOnError: true
})
const hierarchy = computed(() => taskHierarchy(table.tableData.value))
const columns = [
  { title: '任务', key: 'title', width: 350, align: 'left' as const },
  { title: '负责人', dataIndex: 'assigneeName' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'actions', width: 100 }
]
async function link(row: TaskRow) {
  if (busyId.value) return
  busyId.value = row.id
  error.value = ''
  const requestKey = requestKeys.get(row.id) || uuid()
  requestKeys.set(row.id, requestKey)
  try {
    await api.recordLink({
      context: {
        applicationId: props.context.applicationId,
        pageId: props.context.pageId,
        nodeId: props.context.nodeId,
        recordId: props.context.recordId
      },
      taskId: row.id,
      expectedRevision: row.revision,
      include: true,
      requestKey
    })
    emit('linked')
    requestKeys.delete(row.id)
    await table.fetchData()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busyId.value = ''
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="关联已有任务"
    root-class-name="task-hierarchy-drawer"
    display-mode="drawer"
    :allow-switch-display="false"
    :show-footer="false"
    :width="900"
    @cancel="!busyId && emit('close')"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <p>选择当前有权关联的任务。关联仅增加本记录旁的任务引用，保留任务原来的项目和业务材料。</p>
      <OsTablePage
        :columns="columns"
        :data-source="table.tableData.value"
        :loading="table.loading.value"
        :pagination="table.pagination"
        :show-index="false"
        :scroll="{ x: 'max-content' }"
        @change="table.handleTableChange"
      >
        <template #search>
          <a-input-search
            v-model:value="table.queryForm.search"
            placeholder="搜索已有任务"
            aria-label="搜索已有任务"
            enter-button="查询"
            @search="table.handleQuery"
          />
        </template>
        <template #bodyCell="{ column, record }">
          <TaskHierarchyCell v-if="column.key === 'title'" :item="hierarchy.get(record.id)!">
            {{ record.title }}
          </TaskHierarchyCell>
          <template v-else-if="column.key === 'status'">
            {{ taskStates[record.status as keyof typeof taskStates] }}
          </template>
          <a-button
            v-else-if="column.key === 'actions'"
            type="link"
            :loading="busyId === record.id"
            :disabled="!!busyId && busyId !== record.id"
            @click="link(record)"
          >
            关联
          </a-button>
        </template>
      </OsTablePage>
    </template>
  </OsModalForm>
</template>
