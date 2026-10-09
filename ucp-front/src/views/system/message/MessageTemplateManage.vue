<script setup lang="ts">
import { ref } from 'vue'
import type { TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { pageSysMsgTemplate, deleteSysMsgTemplate } from '@/api/message/message'
import SysMsgTemplateFormDrawer from './components/SysMsgTemplateFormDrawer.vue'

type MsgTemplateQuery = { keyword?: string }

const { loading, tableData, pagination, queryForm, handleQuery, handleReset, handleTableChange, fetchData } =
  useOsTablePage<any, MsgTemplateQuery>({
    fetchFn: (params: any) =>
      pageSysMsgTemplate({
        current: params.pageNum,
        size: params.pageSize,
        keyword: params.keyword
      }),
    defaultQuery: () => ({
      keyword: undefined
    }),
    dataField: 'records'
  })

const columns: TableColumnType[] = [
  { title: '编码', dataIndex: 'code', key: 'code', width: 260 },
  { title: '名称', dataIndex: 'name', key: 'name', align: 'left' },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 160, fixed: 'right' }
]

const editorVisible = ref(false)
const currentRecord = ref<any>(null)

function handleAdd() {
  currentRecord.value = null
  editorVisible.value = true
}

function handleEdit(record: any) {
  currentRecord.value = { ...record }
  editorVisible.value = true
}

async function handleDelete(record: any) {
  try {
    await deleteSysMsgTemplate(record.id)
    message.success('删除成功')
    fetchData()
  } catch {
    message.error('删除失败')
  }
}

function handleEditorSuccess() {
  fetchData()
}
</script>

<template>
  <OsTablePage
    :columns="columns"
    :data-source="tableData"
    :loading="loading"
    :pagination="pagination"
    row-key="id"
    title="消息模板列表"
    :scroll="{ y: 'calc(100vh - 360px)' }"
    @change="handleTableChange"
    @search="handleQuery"
  >
    <!-- 搜索区域 -->
    <template #search="{ triggerSearch }">
      <a-form layout="inline" :model="queryForm">
        <a-input-search
          v-model:value="queryForm.keyword"
          placeholder="模板编码/名称"
          allow-clear
          @search="triggerSearch"
          style="width: 220px"
        />
      </a-form>
    </template>

    <!-- 操作按钮 -->
    <template #actions>
      <a-button type="primary" @click="handleAdd">
        <PlusOutlined />
        消息模板
      </a-button>
    </template>

    <!-- 操作列 -->
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'action'">
        <a-space :size="0">
          <template #split>
            <a-divider type="vertical" style="width: 3px; margin: 0px 6px" />
          </template>
          <a-button type="link" style="color: #4338ca; padding: 0 6px; font-weight: 500" @click="handleEdit(record)">
            <EditOutlined />
            编辑
          </a-button>
          <a-popconfirm title="确定要删除吗？" @confirm="handleDelete(record)">
            <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
              <DeleteOutlined />
              删除
            </a-button>
          </a-popconfirm>
        </a-space>
      </template>
    </template>
  </OsTablePage>

  <SysMsgTemplateFormDrawer
    :visible="editorVisible"
    :record="currentRecord"
    @close="editorVisible = false"
    @success="handleEditorSuccess"
  />
</template>

<style scoped>
.msg-template-manage {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.msg-template-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.msg-template-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
