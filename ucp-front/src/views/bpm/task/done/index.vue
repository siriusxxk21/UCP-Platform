<script lang="ts" setup>
import { useRouter } from 'vue-router'
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import type { BpmTaskApi } from '@/api/bpm/task'
import { getTaskDonePage, withdrawTask } from '@/api/bpm/task'
import { EyeOutlined, FileDoneOutlined, ReloadOutlined, RollbackOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { formatDateTime } from '@/utils/format'
import { formatDuration, formatSummary, getTaskStatusMeta, taskStatusOptions } from '../shared'
import { useTaskList } from '../use-task-list'
import FlowWorkDrawer from '../FlowWorkDrawer.vue'
import { WorkDraftState } from '@/types/nocode/work'

defineOptions({ name: 'BpmDoneTask' })
const router = useRouter()
const workOpen = ref(false)
const withdrawTaskId = ref('')
const {
  loading,
  tableData,
  queryForm,
  pagination,
  handleSearch,
  handleReset,
  handleTableChange,
  loadData,
  loadError,
  optionsError,
  optionsLoading,
  loadOptions,
  categoryOptions,
  processDefinitionOptions
} = useTaskList(getTaskDonePage)

const columns = [
  {
    title: '流程',
    dataIndex: ['processInstance', 'name'],
    key: 'processName',
    width: 200,
    ellipsis: true,
    align: 'left' as const
  },
  { title: '摘要', dataIndex: ['processInstance', 'summary'], key: 'summary', width: 240 },
  { title: '发起人', dataIndex: ['processInstance', 'startUser', 'nickname'], key: 'startUser', width: 120 },
  { title: '发起时间', dataIndex: ['processInstance', 'createTime'], key: 'processCreateTime', width: 180 },
  { title: '任务名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true, align: 'left' as const },
  { title: '任务开始时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '任务结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '办理状态', dataIndex: 'status', key: 'status', width: 120 },
  { title: '办理意见', dataIndex: 'reason', key: 'reason', width: 180, ellipsis: true, align: 'left' as const },
  { title: '耗时', dataIndex: 'durationInMillis', key: 'durationInMillis', width: 140 },
  { title: '流程编号', dataIndex: 'processInstanceId', key: 'processInstanceId', width: 260, ellipsis: true },
  { title: '任务编号', dataIndex: 'id', key: 'id', width: 260, ellipsis: true },
  { title: '操作', key: 'action', width: 150, fixed: 'right' as const }
]

function handleHistory(row: BpmTaskApi.Task) {
  router.push({
    name: 'TaskInstanceDetail',
    query: {
      id: row.processInstance?.id || row.processInstanceId,
      taskId: row.id
    }
  })
}

async function handleWithdraw(row: BpmTaskApi.Task) {
  if (withdrawTaskId.value || row.withdrawable !== true) return
  withdrawTaskId.value = row.id
  try {
    await withdrawTask(row.id)
    message.success('撤回成功')
    await loadData()
  } catch (error: any) {
    console.error('撤回任务失败:', error)
    message.error(error.message || '撤回任务失败')
  } finally {
    withdrawTaskId.value = ''
  }
}
</script>
<template>
  <div class="bpm-task-page">
    <OsTablePage
      resizable
      show-column-settings
      column-settings-key="bpm-task-done"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 2160 }"
      row-key="id"
      title="已办任务"
      @change="handleTableChange"
      @search="handleSearch"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="任务名称">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              class="query-control"
              placeholder="请输入任务名称"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="所属流程">
            <a-select
              v-model:value="queryForm.processDefinitionKey"
              :options="processDefinitionOptions.map(item => ({ label: item.name, value: item.key }))"
              allow-clear
              class="query-control"
              option-filter-prop="label"
              placeholder="请选择流程定义"
              show-search
            />
          </a-form-item>
          <a-form-item label="流程分类">
            <a-select
              v-model:value="queryForm.category"
              :options="categoryOptions.map(item => ({ label: item.name, value: item.code }))"
              allow-clear
              class="query-control"
              option-filter-prop="label"
              placeholder="请选择流程分类"
              show-search
            />
          </a-form-item>
          <a-form-item label="办理状态">
            <a-select
              v-model:value="queryForm.status"
              :options="taskStatusOptions"
              allow-clear
              class="query-control"
              placeholder="请选择办理状态"
            />
          </a-form-item>
          <a-form-item label="任务创建时间">
            <a-range-picker v-model:value="queryForm.createTime" class="query-range" show-time />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>

        <a-alert v-if="loadError" class="option-alert" :message="loadError" show-icon type="error">
          <template #action><a-button size="small" :loading="loading" @click="loadData">重试</a-button></template>
        </a-alert>
        <a-alert v-if="optionsError" class="option-alert" :message="optionsError" show-icon type="warning">
          <template #action>
            <a-button size="small" :loading="optionsLoading" @click="loadOptions">重试选项</a-button>
          </template>
        </a-alert>
      </template>

      <template #toolbar>
        <a-button @click="workOpen = true">
          <FileDoneOutlined />
          我的流程材料
        </a-button>
        <a-button :loading="loading" @click="loadData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'summary'">
          <span class="summary-text">{{ formatSummary(record.processInstance?.summary) }}</span>
        </template>
        <template v-else-if="column.key === 'processCreateTime'">
          {{ formatDateTime(record.processInstance?.createTime) }}
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'endTime'">
          {{ formatDateTime(record.endTime) }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="getTaskStatusMeta(record.status).color">
            {{ getTaskStatusMeta(record.status).label }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'durationInMillis'">
          {{ formatDuration(record.durationInMillis) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-popconfirm
              v-if="record.status === 2 && record.withdrawable === true"
              :disabled="!!withdrawTaskId"
              title="确定要撤回该任务吗？"
              @confirm="handleWithdraw(record)"
            >
              <a-button
                :loading="withdrawTaskId === record.id"
                :disabled="!!withdrawTaskId && withdrawTaskId !== record.id"
                danger
                size="small"
                type="link"
              >
                <RollbackOutlined />
                撤回
              </a-button>
            </a-popconfirm>
            <a-button size="small" type="link" @click="handleHistory(record)">
              <EyeOutlined />
              历史
            </a-button>
          </a-space>
        </template>
      </template>
    </OsTablePage>
    <FlowWorkDrawer v-model:open="workOpen" :initial-state="WorkDraftState.SUBMITTED" />
  </div>
</template>

<style scoped>
.bpm-task-page {
  height: 100%;
}

.query-form {
  row-gap: 12px;
}

.query-control {
  width: 200px;
}

.query-range {
  width: 360px;
}

.option-alert {
  margin-top: 12px;
}

.summary-text {
  white-space: pre-line;
}
</style>
