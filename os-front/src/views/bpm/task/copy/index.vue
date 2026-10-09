<script lang="ts" setup>
import { useRouter } from 'vue-router'
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import { getProcessInstanceCopyPage } from '@/api/bpm/processInstance'
import { EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { formatDateTime } from '@/utils/format'
import { formatSummary } from '../shared'
import { useTaskList } from '../use-task-list'

defineOptions({ name: 'BpmCopyTask' })
const router = useRouter()
const { loading, tableData, queryForm, pagination, handleSearch, handleReset, handleTableChange, loadData, loadError } =
  useTaskList(getProcessInstanceCopyPage, false)

const columns = [
  { title: '流程名称', dataIndex: 'processInstanceName', key: 'processInstanceName', width: 200, ellipsis: true },
  { title: '摘要', dataIndex: 'summary', key: 'summary', width: 240 },
  { title: '流程发起人', dataIndex: ['startUser', 'nickname'], key: 'startUser', width: 120 },
  { title: '流程发起时间', dataIndex: 'processInstanceStartTime', key: 'processInstanceStartTime', width: 180 },
  { title: '抄送节点', dataIndex: 'activityName', key: 'activityName', width: 140, ellipsis: true },
  { title: '抄送人', dataIndex: ['createUser', 'nickname'], key: 'createUser', width: 120 },
  { title: '抄送意见', dataIndex: 'reason', key: 'reason', width: 180, ellipsis: true },
  { title: '抄送时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 100, fixed: 'right' as const }
]

function handleDetail(row: BpmProcessInstanceApi.ProcessInstanceCopyRespVO) {
  router.push({
    name: 'TaskInstanceDetail',
    query: {
      id: row.processInstanceId,
      ...(row.activityId ? { activityId: row.activityId } : {})
    }
  })
}
</script>
<template>
  <div class="bpm-task-page">
    <OsTablePage
      resizable
      show-column-settings
      column-settings-key="bpm-task-copy"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1460 }"
      row-key="id"
      title="抄送任务"
      @change="handleTableChange"
      @search="handleSearch"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="流程名称">
            <a-input
              v-model:value="queryForm.processInstanceName"
              allow-clear
              class="query-control"
              placeholder="请输入流程名称"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="抄送时间">
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
      </template>

      <template #toolbar>
        <a-button :loading="loading" @click="loadData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'summary'">
          <span class="summary-text">{{ formatSummary(record.summary) }}</span>
        </template>
        <template v-else-if="column.key === 'processInstanceStartTime'">
          {{ formatDateTime(record.processInstanceStartTime) }}
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button size="small" type="link" @click="handleDetail(record)">
            <EyeOutlined />
            详情
          </a-button>
        </template>
      </template>
    </OsTablePage>
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
  width: 220px;
}

.query-range {
  width: 360px;
}

.summary-text {
  white-space: pre-line;
}
.option-alert {
  margin-top: 12px;
}
</style>
