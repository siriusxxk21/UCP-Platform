<script lang="ts" setup>
import type { Dayjs } from 'dayjs'
import type { FormInstance, TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import type { BpmCategoryApi } from '@/api/bpm/category'
import { createCategory, deleteCategory, getCategory, getCategoryPage, updateCategory } from '@/api/bpm/category'
import type { DynamicSearchField } from '@/components/ucp-table-page/types'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { computed, reactive, ref } from 'vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { formatDateTime } from '@/utils/format'

defineOptions({ name: 'BpmCategory' })

interface QueryForm {
  name?: string
  code?: string
  status?: number
  createTime?: [Dayjs, Dayjs]
}

interface CategoryForm {
  id?: number
  name: string
  code: string
  description?: string
  status: number
  sort?: number
}

const submitLoading = ref(false)
const modalOpen = ref(false)
const formRef = ref<FormInstance>()
const formModel = reactive<CategoryForm>({
  name: '',
  code: '',
  description: '',
  status: 0,
  sort: 0
})

const modalTitle = computed(() => (formModel.id ? '编辑流程分类' : '新建流程分类'))

const statusOptions = [
  { label: '启用', value: 0 },
  { label: '禁用', value: 1 }
]

const tagMap = {
  status: {
    0: { label: '启用', color: 'success' },
    1: { label: '禁用', color: 'default' }
  }
}

const formRules = {
  name: [{ required: true, message: '请输入分类名', trigger: 'blur' }],
  code: [{ required: true, message: '请输入分类标志', trigger: 'blur' }]
}

const columns: TableColumnType[] = [
  { title: '分类编号', dataIndex: 'id', key: 'id', width: 110 },
  { title: '分类名', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
  { title: '分类标志', dataIndex: 'code', key: 'code', width: 160, ellipsis: true },
  { title: '分类描述', dataIndex: 'description', key: 'description', width: 240, ellipsis: true },
  { title: '分类状态', dataIndex: 'status', key: 'status', width: 110 },
  { title: '分类排序', dataIndex: 'sort', key: 'sort', width: 110 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 150, fixed: 'right' as const }
]

const dynamicSearchFields: DynamicSearchField[] = [
  { field: 'name', label: '分类名', type: 'text' },
  { field: 'code', label: '分类标志', type: 'text' },
  { field: 'status', label: '分类状态', type: 'select', operators: ['eq'], options: statusOptions },
  { field: 'createTime', label: '创建时间', type: 'datetimeRange' }
]

function formatRange(range?: [Dayjs, Dayjs]) {
  if (!range?.length) return undefined
  return [range[0].format('YYYY-MM-DD HH:mm:ss'), range[1].format('YYYY-MM-DD HH:mm:ss')]
}

const {
  loading,
  tableData,
  pagination,
  queryForm,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData,
  handleDynamicSearch
} = useOsTablePage<BpmCategoryApi.Category, QueryForm>({
  fetchFn: params =>
    getCategoryPage({
      ...params,
      pageNo: params.pageNum,
      name: params.name?.trim() || undefined,
      code: params.code?.trim() || undefined,
      createTime: formatRange(params.createTime)
    } as any),
  defaultQuery: () => ({
    name: undefined,
    code: undefined,
    status: undefined,
    createTime: undefined
  }),
  dataField: 'list',
  onError: (error: any) => {
    console.error('加载流程分类失败:', error)
    message.error(error.message || '加载流程分类失败')
  }
})

function resetForm() {
  Object.assign(formModel, {
    id: undefined,
    name: '',
    code: '',
    description: '',
    status: 0,
    sort: 0
  })
  formRef.value?.clearValidate()
}

function handleCreate() {
  resetForm()
  modalOpen.value = true
}

async function handleEdit(row: BpmCategoryApi.Category) {
  if (!row.id) return
  resetForm()
  modalOpen.value = true
  submitLoading.value = true
  try {
    const detail = await getCategory(row.id)
    Object.assign(formModel, detail)
  } catch (error: any) {
    console.error('加载流程分类详情失败:', error)
    message.error(error.message || '加载流程分类详情失败')
  } finally {
    submitLoading.value = false
  }
}

async function handleSubmit() {
  await formRef.value?.validate()
  submitLoading.value = true
  try {
    const payload = { ...formModel } as BpmCategoryApi.Category
    if (payload.id) {
      await updateCategory(payload)
    } else {
      await createCategory(payload)
    }
    message.success('保存成功')
    modalOpen.value = false
    fetchData()
  } catch (error: any) {
    console.error('保存流程分类失败:', error)
    message.error(error.message || '保存流程分类失败')
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(row: BpmCategoryApi.Category) {
  if (!row.id) return
  try {
    await deleteCategory(row.id)
    message.success('删除成功')
    fetchData()
  } catch (error: any) {
    console.error('删除流程分类失败:', error)
    message.error(error.message || '删除流程分类失败')
  }
}
</script>

<template>
  <div class="bpm-category-page">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :dynamic-search-fields="dynamicSearchFields"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1240 }"
      :tag-map="tagMap"
      advanced-search-mode="dynamic"
      column-settings-key="bpm-category-list"
      resizable
      row-key="id"
      search-storage-key="bpm-category-list"
      show-advanced-search
      show-column-settings
      title="流程分类"
      @change="handleTableChange"
      @search="handleQuery"
      @dynamic-search="handleDynamicSearch"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="分类名">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              class="query-control"
              placeholder="请输入分类名"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="分类标志">
            <a-input
              v-model:value="queryForm.code"
              allow-clear
              class="query-control"
              placeholder="请输入分类标志"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="分类状态">
            <a-select
              v-model:value="queryForm.status"
              :options="statusOptions"
              allow-clear
              class="query-control"
              placeholder="请选择分类状态"
            />
          </a-form-item>
          <a-form-item label="创建时间">
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
      </template>

      <template #actions>
        <a-button type="primary" @click="handleCreate">
          <PlusOutlined />
          新建流程分类
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">
          {{ formatDateTime((record as BpmCategoryApi.Category).createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button size="small" type="link" @click="handleEdit(record as BpmCategoryApi.Category)">
              <EditOutlined />
              编辑
            </a-button>
            <a-popconfirm title="确定要删除该流程分类吗？" @confirm="handleDelete(record as BpmCategoryApi.Category)">
              <a-button danger size="small" type="link">
                <DeleteOutlined />
                删除
              </a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </OsTablePage>

    <a-modal
      v-model:open="modalOpen"
      :confirm-loading="submitLoading"
      :title="modalTitle"
      @cancel="resetForm"
      @ok="handleSubmit"
    >
      <a-form ref="formRef" :model="formModel" :rules="formRules" layout="vertical">
        <a-form-item label="分类名" name="name">
          <a-input v-model:value="formModel.name" placeholder="请输入分类名" />
        </a-form-item>
        <a-form-item label="分类标志" name="code">
          <a-input v-model:value="formModel.code" placeholder="请输入分类标志" />
        </a-form-item>
        <a-form-item label="分类描述" name="description">
          <a-textarea v-model:value="formModel.description" :auto-size="{ minRows: 3 }" placeholder="请输入分类描述" />
        </a-form-item>
        <a-form-item label="分类状态" name="status">
          <a-radio-group v-model:value="formModel.status">
            <a-radio :value="0">启用</a-radio>
            <a-radio :value="1">禁用</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="分类排序" name="sort">
          <a-input-number v-model:value="formModel.sort" :min="0" class="full-control" placeholder="请输入分类排序" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.bpm-category-page {
  height: 100%;
}

.query-form {
  row-gap: 12px;
}

.query-control {
  width: 180px;
}

.query-range {
  width: 360px;
}

.full-control {
  width: 100%;
}
</style>
