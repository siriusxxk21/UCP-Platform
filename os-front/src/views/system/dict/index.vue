<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  OrderedListOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useOsModalForm } from '@/composables/useOsModalForm'
import {
  createDictType,
  deleteDictTypes,
  exportDictTypes,
  getDictType,
  getDictTypePage,
  updateDictType
} from '@/api/system/dict'
import { DICT_STATUS_OPTIONS, DICT_TAG_MAP } from '@/types/system/dict'
import type { DictId, DictType, DictTypeQuery, DictTypeSave } from '@/types/system/dict'
import { hasPermission } from '@/utils/access'
import { formatDateTime } from '@/utils/format'
import { today, triggerDownload } from '@/utils/file'
import DictDataPanel from './DictDataPanel.vue'
import './dict.css'

defineOptions({ name: 'SystemDict' })

const route = useRoute()
const router = useRouter()
const selectedTypeId = computed(() => (typeof route.query.dictTypeId === 'string' ? route.query.dictTypeId : ''))
const canCreate = computed(() => hasPermission('system:dict:create'))
const canUpdate = computed(() => hasPermission('system:dict:update'))
const canDelete = computed(() => hasPermission('system:dict:delete'))
const canExport = computed(() => hasPermission('system:dict:export'))

const columns: TableColumnType[] = [
  { title: '字典名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
  { title: '字典编码', dataIndex: 'type', key: 'type', width: 220, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '备注', dataIndex: 'remark', key: 'remark', width: 220, ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 350, fixed: 'right' }
]

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  selectedRows,
  handleQuery,
  handleReset,
  fetchData,
  clearSelection,
  updateSelection,
  handleTableChange: changeTable
} = useOsTablePage<DictType, DictTypeQuery>({
  fetchFn: getDictTypePage,
  defaultQuery: () => ({ name: undefined, type: undefined, status: undefined }),
  dataField: 'list'
})

// 删除最后一页后回退到有效页码，查询和翻页时清除批量选择。
async function refreshAfterDelete(count: number) {
  clearSelection()
  pagination.current = Math.min(
    pagination.current,
    Math.max(1, Math.ceil((pagination.total - count) / pagination.pageSize))
  )
  await fetchData()
}

function search() {
  clearSelection()
  handleQuery()
}
function reset() {
  clearSelection()
  handleReset()
}
function changePage(page: { current: number; pageSize: number }) {
  clearSelection()
  changeTable({ ...page, current: page.pageSize === pagination.pageSize ? page.current : 1 })
}

const modalForm = useOsModalForm<DictTypeSave>({
  createFn: async data => {
    await createDictType(data)
  },
  updateFn: async data => {
    await updateDictType(data)
  },
  defaultForm: () => ({ id: undefined, name: '', type: '', status: 0, remark: '' }),
  beforeSubmit: data => ({ ...data, name: data.name.trim(), type: data.type.trim(), remark: data.remark || '' }),
  afterSuccess: () => {
    clearSelection()
    void fetchData()
  },
  titles: { add: '新增字典类型', edit: '编辑字典类型' }
})

const formRules = {
  name: [
    { required: true, whitespace: true, message: '请输入字典名称' },
    { max: 100, message: '最多 100 个字符' }
  ],
  type: [
    { required: true, whitespace: true, message: '请输入字典编码' },
    { max: 100, message: '最多 100 个字符' }
  ],
  status: [{ required: true, message: '请选择状态' }],
  remark: [{ max: 500, message: '最多 500 个字符' }]
}

async function edit(record: DictType) {
  try {
    const data = await getDictType(record.id)
    if (!data) {
      message.warning('该字典已删除，请刷新列表')
      return
    }
    modalForm.openEdit(data)
  } catch {
    /* 统一请求层展示错误。 */
  }
}

async function toggleStatus(record: DictType) {
  try {
    const data = await getDictType(record.id)
    if (!data) {
      message.warning('该字典已删除，请刷新列表')
      return
    }
    await updateDictType({
      id: data.id,
      name: data.name,
      type: data.type,
      status: record.status === 0 ? 1 : 0,
      remark: data.remark || ''
    })
    message.success(record.status === 0 ? '禁用成功' : '启用成功')
    await fetchData()
  } catch {
    /* 统一请求层展示错误。 */
  }
}

async function remove(ids: DictId[]) {
  try {
    await deleteDictTypes(ids)
    message.success('删除成功')
    await refreshAfterDelete(ids.length)
  } catch {
    /* 删除保护由后端统一校验。 */
  }
}

async function exportRows() {
  try {
    triggerDownload(await exportDictTypes({ ...queryForm }), `字典类型_${today()}.xlsx`)
  } catch {
    /* 统一请求层展示错误。 */
  }
}

function openData(record: DictType) {
  void router.push({ path: route.path, query: { ...route.query, dictTypeId: String(record.id) } })
}

function backToTypes() {
  const { dictTypeId: _id, ...query } = route.query
  void router.push({ path: route.path, query })
  void fetchData()
}
</script>

<template>
  <div class="dict-manage">
    <OsTablePage
      v-show="!selectedTypeId"
      class="dict-table"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      title="字典类型列表"
      :scroll="{ x: 'max-content' }"
      :row-selection="canDelete"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      :show-export="canExport"
      export-text="导出查询结果"
      show-column-settings
      column-settings-key="system-dict-types"
      resizable
      :tag-map="DICT_TAG_MAP"
      @change="changePage"
      @search="search"
      @selection-change="updateSelection"
      @batch-delete="remove"
      @export="exportRows"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="字典名称">
            <a-input
              v-model:value="queryForm.name"
              class="dict-search-input"
              placeholder="请输入字典名称"
              allow-clear
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="字典编码">
            <a-input
              v-model:value="queryForm.type"
              class="dict-search-input"
              placeholder="请输入字典编码"
              allow-clear
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="状态">
            <a-select
              v-model:value="queryForm.status"
              class="dict-search-status"
              :options="DICT_STATUS_OPTIONS"
              placeholder="全部"
              allow-clear
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="reset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template #actions>
        <a-button v-if="canCreate" type="primary" @click="modalForm.openAdd">
          <PlusOutlined />
          新增
        </a-button>
        <a-button :loading="loading" @click="fetchData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
        <template v-else-if="column.key === 'action'">
          <div class="dict-actions">
            <a-button type="link" @click="openData(record)">
              <OrderedListOutlined />
              字典数据
            </a-button>
            <a-button v-if="canUpdate" type="link" @click="edit(record)">
              <EditOutlined />
              编辑
            </a-button>
            <a-popconfirm
              v-if="canUpdate"
              :title="`确定${record.status === 0 ? '禁用' : '启用'}该字典类型吗？`"
              ok-text="确定"
              cancel-text="取消"
              @confirm="toggleStatus(record)"
            >
              <a-button type="link" :danger="record.status === 0">
                <StopOutlined v-if="record.status === 0" />
                <CheckCircleOutlined v-else />
                {{ record.status === 0 ? '禁用' : '启用' }}
              </a-button>
            </a-popconfirm>
            <a-popconfirm
              v-if="canDelete"
              title="确定删除该字典类型吗？存在字典数据时无法删除。"
              ok-text="确定"
              cancel-text="取消"
              @confirm="remove([record.id])"
            >
              <a-button type="link" danger>
                <DeleteOutlined />
                删除
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </template>
    </OsTablePage>
    <DictDataPanel v-if="selectedTypeId" :key="selectedTypeId" :type-id="selectedTypeId" @back="backToTypes" />
    <OsModalForm v-bind="modalForm.modalProps.value" v-on="modalForm.modalEvents" :rules="formRules" width="620px">
      <template #formItems="{ formData }">
        <a-form-item label="字典名称" name="name">
          <a-input v-model:value="formData.name" placeholder="请输入字典名称" :maxlength="100" />
        </a-form-item>
        <a-form-item label="字典编码" name="type" extra="创建后不可修改，用于业务引用，如 system_order_status。">
          <a-input
            v-model:value="formData.type"
            :disabled="modalForm.isEdit.value"
            placeholder="请输入字典编码"
            :maxlength="100"
          />
        </a-form-item>
        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formData.status" :options="DICT_STATUS_OPTIONS" />
        </a-form-item>
        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="formData.remark" placeholder="请输入备注" :maxlength="500" :rows="3" show-count />
        </a-form-item>
      </template>
    </OsModalForm>
  </div>
</template>
