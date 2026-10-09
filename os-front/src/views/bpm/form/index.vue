<script lang="ts" setup>
import type { TablePaginationConfig } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import type { BpmFormApi } from '@/api/bpm/form'
import { deleteForm, getFormPage } from '@/api/bpm/form'
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons-vue'
import { onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatDateTime } from '@/utils/format'
import FormDetailModal from './modules/detail.vue'

defineOptions({ name: 'BpmForm' })

const router = useRouter()
const loading = ref(false)
const tableData = ref<BpmFormApi.Form[]>([])
const detailOpen = ref(false)
const currentDetailId = ref<string | number>()
const queryForm = reactive<{ name?: string }>({})
const pagination = reactive<TablePaginationConfig>({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const columns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 100 },
  { title: '表单名称', dataIndex: 'name', key: 'name', width: 220, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '备注', dataIndex: 'remark', key: 'remark', width: 240, ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 260, fixed: 'right' as const }
]

async function loadData() {
  loading.value = true
  try {
    const page = await getFormPage({
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
      name: queryForm.name?.trim() || undefined
    })
    tableData.value = page.list || page.records || []
    pagination.total = page.total || 0
  } catch (error: any) {
    console.error('加载流程表单失败:', error)
    message.error(error.message || '加载流程表单失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  loadData()
}

function handleReset() {
  queryForm.name = undefined
  handleSearch()
}

function handleTableChange(pag: TablePaginationConfig) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 10
  loadData()
}

function openDesigner(query: Record<string, string | number | undefined>) {
  router.push({ path: '/bpm/form/designer', query })
}

function handleCreate() {
  openDesigner({ type: 'create' })
}

function handleEdit(row: BpmFormApi.Form) {
  openDesigner({ id: row.id, type: 'edit' })
}

function handleCopy(row: BpmFormApi.Form) {
  openDesigner({ copyId: row.id, type: 'copy' })
}

function handleDetail(row: BpmFormApi.Form) {
  currentDetailId.value = row.id
  detailOpen.value = true
}

async function handleDelete(row: BpmFormApi.Form) {
  if (!row.id) return
  try {
    await deleteForm(row.id)
    message.success('删除成功')
    loadData()
  } catch (error: any) {
    console.error('删除流程表单失败:', error)
    message.error(error.message || '删除流程表单失败')
  }
}

onMounted(loadData)
onActivated(loadData)
</script>

<template>
  <div class="bpm-form-page">
    <a-card :bordered="false">
      <template #title>
        <div class="page-title">流程表单</div>
      </template>
      <template #extra>
        <a-button type="primary" @click="handleCreate">
          <PlusOutlined />
          新建流程表单
        </a-button>
      </template>

      <a-form :model="queryForm" class="query-form" layout="inline">
        <a-form-item label="表单名称">
          <a-input
            v-model:value="queryForm.name"
            allow-clear
            class="query-control"
            placeholder="请输入表单名称"
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
        :scroll="{ x: 1100 }"
        row-key="id"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="record.status === 0 ? 'success' : 'default'">
              {{ record.status === 0 ? '启用' : '禁用' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ formatDateTime(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" type="link" @click="handleCopy(record)">
                <CopyOutlined />
                复制
              </a-button>
              <a-button size="small" type="link" @click="handleEdit(record)">
                <EditOutlined />
                编辑
              </a-button>
              <a-button size="small" type="link" @click="handleDetail(record)">
                <EyeOutlined />
                详情
              </a-button>
              <a-popconfirm title="确定要删除该流程表单吗？" @confirm="handleDelete(record)">
                <a-button danger size="small" type="link">
                  <DeleteOutlined />
                  删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <FormDetailModal v-model:open="detailOpen" :form-id="currentDetailId" />
  </div>
</template>

<style scoped>
.bpm-form-page {
  height: 100%;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.query-form {
  row-gap: 12px;
  margin-bottom: 16px;
}

.query-control {
  width: 240px;
}
@media (max-width: 767px) {
  .query-control {
    width: 100%;
  }
  .query-form :deep(.ant-form-item) {
    width: 100%;
    margin-right: 0;
  }
  .bpm-form-page :deep(.ant-card-body) {
    padding: 12px;
  }
}
</style>
