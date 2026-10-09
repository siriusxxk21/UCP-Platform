<script lang="ts" setup>
import type { TablePaginationConfig } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { getProcessDefinitionPage } from '@/api/bpm/definition'
import { ArrowLeftOutlined, EyeOutlined, ReloadOutlined, RollbackOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formatDateTime } from '@/utils/format'
import FormDetailModal from '../../form/modules/detail.vue'

defineOptions({ name: 'BpmProcessDefinition' })

const BPM_MODEL_TYPE_LABEL: Record<number, string> = {
  10: 'BPMN 设计器',
  20: 'SIMPLE 设计器'
}

const BPM_MODEL_FORM_TYPE = {
  NORMAL: 10,
  CUSTOM: 20
}

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const tableData = ref<BpmProcessDefinitionApi.ProcessDefinition[]>([])
const detailOpen = ref(false)
const currentFormId = ref<string | number>()
const queryForm = reactive({
  key: String(route.query.key || ''),
  name: ''
})
const pagination = reactive<TablePaginationConfig>({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const columns = [
  { title: '定义编号', dataIndex: 'id', key: 'id', width: 260, ellipsis: true },
  { title: '流程名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
  { title: '流程图标', dataIndex: 'icon', key: 'icon', width: 100 },
  { title: '可见范围', dataIndex: 'startUsers', key: 'startUsers', width: 160 },
  { title: '流程类型', dataIndex: 'modelType', key: 'modelType', width: 140 },
  { title: '表单信息', dataIndex: 'formType', key: 'formInfo', width: 180 },
  { title: '流程版本', dataIndex: 'version', key: 'version', width: 100 },
  { title: '部署时间', dataIndex: 'deploymentTime', key: 'deploymentTime', width: 180 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' as const }
]

function visibleScopeText(row: BpmProcessDefinitionApi.ProcessDefinition) {
  if (!row.startUsers?.length) return '全部可见'
  if (row.startUsers.length === 1) return row.startUsers[0].nickname
  return `${row.startUsers[0].nickname}等 ${row.startUsers.length} 人可见`
}

function visibleScopeTooltip(row: BpmProcessDefinitionApi.ProcessDefinition) {
  return row.startUsers?.map(user => user.nickname).join('、') || ''
}

function modelTypeText(type?: number) {
  return type ? BPM_MODEL_TYPE_LABEL[type] || String(type) : '-'
}

async function loadData() {
  loading.value = true
  try {
    const page = await getProcessDefinitionPage({
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
      key: queryForm.key?.trim() || undefined,
      name: queryForm.name?.trim() || undefined
    } as any)
    tableData.value = page.list || page.records || []
    pagination.total = page.total || 0
  } catch (error: any) {
    console.error('加载流程定义失败:', error)
    message.error(error.message || '加载流程定义失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  loadData()
}

function handleReset() {
  queryForm.key = ''
  queryForm.name = ''
  handleSearch()
}

function handleTableChange(pag: TablePaginationConfig) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 10
  loadData()
}

function handleFormDetail(row: BpmProcessDefinitionApi.ProcessDefinition) {
  if (row.formType === BPM_MODEL_FORM_TYPE.NORMAL) {
    currentFormId.value = row.formId
    detailOpen.value = true
  } else if (row.formCustomCreatePath) {
    router.push(row.formCustomCreatePath)
  }
}

function handleRecover(row: BpmProcessDefinitionApi.ProcessDefinition) {
  router.push({ path: '/bpm/model/form', query: { id: row.id, type: 'definition' } })
}

function handleBack() {
  router.push('/bpm/model')
}

onMounted(loadData)
</script>

<template>
  <div class="bpm-definition-page">
    <a-card :bordered="false">
      <template #title>
        <a-space>
          <a-button type="text" @click="handleBack">
            <ArrowLeftOutlined />
          </a-button>
          <span>流程定义</span>
        </a-space>
      </template>
      <template #extra>
        <a-button @click="loadData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>

      <a-form :model="queryForm" class="query-form" layout="inline">
        <a-form-item label="流程标识">
          <a-input
            v-model:value="queryForm.key"
            allow-clear
            class="query-control"
            placeholder="请输入流程标识"
            @press-enter="handleSearch"
          />
        </a-form-item>
        <a-form-item label="流程名称">
          <a-input
            v-model:value="queryForm.name"
            allow-clear
            class="query-control"
            placeholder="请输入流程名称"
            @press-enter="handleSearch"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" @click="handleSearch">
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

      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1330 }"
        row-key="id"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'icon'">
            <a-avatar v-if="record.icon" :size="28" :src="record.icon" shape="square" />
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'startUsers'">
            <a-tooltip :title="visibleScopeTooltip(record)">
              {{ visibleScopeText(record) }}
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'modelType'">
            <a-tag color="blue">{{ modelTypeText(record.modelType) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'formInfo'">
            <a-button
              v-if="record.formType === BPM_MODEL_FORM_TYPE.NORMAL"
              size="small"
              type="link"
              @click="handleFormDetail(record)"
            >
              {{ record.formName || '查看表单' }}
            </a-button>
            <a-button
              v-else-if="record.formCustomCreatePath"
              size="small"
              type="link"
              @click="handleFormDetail(record)"
            >
              {{ record.formCustomCreatePath }}
            </a-button>
            <span v-else>暂无表单</span>
          </template>
          <template v-else-if="column.key === 'version'">
            <a-tag>v{{ record.version }}</a-tag>
          </template>
          <template v-else-if="column.key === 'deploymentTime'">
            {{ formatDateTime(record.deploymentTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" type="link" @click="handleFormDetail(record)">
                <EyeOutlined />
                表单
              </a-button>
              <a-button size="small" type="link" @click="handleRecover(record)">
                <RollbackOutlined />
                恢复
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <FormDetailModal v-model:open="detailOpen" :form-id="currentFormId" />
  </div>
</template>

<style scoped>
.bpm-definition-page {
  height: 100%;
}

.query-form {
  row-gap: 12px;
  margin-bottom: 16px;
}

.query-control {
  width: 220px;
}
</style>
