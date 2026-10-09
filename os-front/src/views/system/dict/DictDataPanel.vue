<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
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
  createDictData,
  deleteDictData,
  exportDictData,
  getDictData,
  getDictDataPage,
  getDictType,
  updateDictData
} from '@/api/system/dict'
import { DICT_COLOR_OPTIONS, DICT_STATUS_OPTIONS, DICT_TAG_MAP, dictTagColor } from '@/types/system/dict'
import type { DictData, DictDataQuery, DictDataSave, DictId, DictType } from '@/types/system/dict'
import { hasPermission } from '@/utils/access'
import { formatDateTime } from '@/utils/format'
import { today, triggerDownload } from '@/utils/file'

const props = defineProps<{ typeId: string }>()
defineEmits<{ back: [] }>()
const dictType = ref<DictType | null>(null)
const contextLoading = ref(false)
const contextError = ref('')
const canCreate = computed(() => hasPermission('system:dict:create'))
const canUpdate = computed(() => hasPermission('system:dict:update'))
const canDelete = computed(() => hasPermission('system:dict:delete'))
const canExport = computed(() => hasPermission('system:dict:export'))
const typeEnabled = computed(() => dictType.value?.status === 0)

const columns: TableColumnType[] = [
  { title: '字典标签', dataIndex: 'label', key: 'label', width: 170, ellipsis: true },
  { title: '字典值', dataIndex: 'value', key: 'value', width: 170, ellipsis: true },
  { title: '显示顺序', dataIndex: 'sort', key: 'sort', width: 100 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '颜色类型', dataIndex: 'colorType', key: 'colorType', width: 110 },
  { title: '样式类名', dataIndex: 'cssClass', key: 'cssClass', width: 150, ellipsis: true },
  { title: '备注', dataIndex: 'remark', key: 'remark', width: 200, ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 250, fixed: 'right' }
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
} = useOsTablePage<DictData, DictDataQuery>({
  // 字典上下文由服务端详情确定，避免未加载或重置条件时误查全部字典数据。
  fetchFn: params =>
    dictType.value
      ? getDictDataPage({ ...params, dictType: dictType.value.type })
      : Promise.resolve({ list: [], total: 0 }),
  defaultQuery: () => ({ dictType: '', label: undefined, status: undefined }),
  immediate: false,
  dataField: 'list'
})

async function loadContext() {
  contextLoading.value = true
  contextError.value = ''
  try {
    dictType.value = await getDictType(props.typeId)
    if (!dictType.value) {
      contextError.value = '该字典类型不存在或已删除'
      return
    }
    await fetchData()
  } catch {
    contextError.value = '字典信息加载失败，请重试'
  } finally {
    contextLoading.value = false
  }
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

const modalForm = useOsModalForm<DictDataSave>({
  createFn: async data => {
    await createDictData(data)
  },
  updateFn: async data => {
    await updateDictData(data)
  },
  defaultForm: () => ({
    id: undefined,
    dictType: dictType.value?.type || '',
    label: '',
    value: '',
    sort: 0,
    status: 0,
    colorType: 'default',
    cssClass: '',
    remark: ''
  }),
  beforeSubmit: data => ({
    ...data,
    label: data.label.trim(),
    value: data.value.trim(),
    colorType: data.colorType || '',
    cssClass: data.cssClass || '',
    remark: data.remark || ''
  }),
  afterSuccess: () => {
    clearSelection()
    void fetchData()
  },
  titles: { add: '新增字典数据', edit: '编辑字典数据' }
})

const formRules = {
  label: [
    { required: true, whitespace: true, message: '请输入字典标签' },
    { max: 100, message: '最多 100 个字符' }
  ],
  value: [
    { required: true, whitespace: true, message: '请输入字典值' },
    { max: 100, message: '最多 100 个字符' }
  ],
  sort: [{ required: true, type: 'integer' as const, min: 0, max: 2147483647, message: '请输入 0～2147483647 的整数' }],
  status: [{ required: true, message: '请选择状态' }],
  colorType: [{ max: 100, message: '最多 100 个字符' }],
  cssClass: [{ max: 100, message: '最多 100 个字符' }],
  remark: [{ max: 500, message: '最多 500 个字符' }]
}

async function edit(record: DictData) {
  try {
    const data = await getDictData(record.id)
    if (!data) {
      message.warning('该字典数据已删除，请刷新列表')
      return
    }
    modalForm.openEdit(data)
  } catch {
    /* 统一请求层展示错误。 */
  }
}

async function toggleStatus(record: DictData) {
  try {
    const data = await getDictData(record.id)
    if (!data) {
      message.warning('该字典数据已删除，请刷新列表')
      return
    }
    const { createTime: _time, ...saveData } = data
    await updateDictData({ ...saveData, status: record.status === 0 ? 1 : 0 })
    message.success(record.status === 0 ? '禁用成功' : '启用成功')
    await fetchData()
  } catch {
    /* 统一请求层展示错误。 */
  }
}

async function remove(ids: DictId[]) {
  try {
    await deleteDictData(ids)
    message.success('删除成功')
    clearSelection()
    pagination.current = Math.min(
      pagination.current,
      Math.max(1, Math.ceil((pagination.total - ids.length) / pagination.pageSize))
    )
    await fetchData()
  } catch {
    /* 统一请求层展示错误。 */
  }
}

async function exportRows() {
  if (!dictType.value) return
  try {
    triggerDownload(await exportDictData({ ...queryForm, dictType: dictType.value.type }), `字典数据_${today()}.xlsx`)
  } catch {
    /* 统一请求层展示错误。 */
  }
}

onMounted(loadContext)
</script>

<template>
  <div class="dict-context">
    <a-button @click="$emit('back')">
      <ArrowLeftOutlined />
      返回字典类型
    </a-button>
    <template v-if="dictType">
      <span class="dict-context-name" :title="dictType.name">{{ dictType.name }}</span>
      <span class="dict-context-code" :title="dictType.type">{{ dictType.type }}</span>
      <a-tag :color="typeEnabled ? 'success' : 'error'">{{ typeEnabled ? '启用' : '禁用' }}</a-tag>
    </template>
  </div>
  <a-result v-if="contextError" status="warning" :title="contextError">
    <template #extra><a-button :loading="contextLoading" @click="loadContext">重新加载</a-button></template>
  </a-result>
  <a-spin v-else-if="!dictType" :spinning="contextLoading" />
  <template v-else>
    <a-alert
      v-if="!typeEnabled"
      message="该字典类型已禁用。启用类型后可新增、编辑或切换字典数据状态。"
      type="info"
      show-icon
    />
    <OsTablePage
      class="dict-table"
      :columns="columns"
      :data-source="tableData"
      :loading="loading || contextLoading"
      :pagination="pagination"
      row-key="id"
      title="字典数据列表"
      :scroll="{ x: 'max-content' }"
      :row-selection="canDelete"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      :show-export="canExport"
      export-text="导出查询结果"
      show-column-settings
      column-settings-key="system-dict-data"
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
          <a-form-item label="字典标签">
            <a-input
              v-model:value="queryForm.label"
              class="dict-search-input"
              placeholder="请输入字典标签"
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
        <a-button v-if="canCreate" type="primary" :disabled="!typeEnabled" @click="modalForm.openAdd">
          <PlusOutlined />
          新增
        </a-button>
        <a-button :loading="contextLoading" @click="loadContext">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'label'">
          <a-tag :color="dictTagColor(record.colorType)" :title="record.label">{{ record.label }}</a-tag>
        </template>
        <template v-else-if="column.key === 'colorType'">
          {{
            DICT_COLOR_OPTIONS.find(option => option.value === record.colorType)?.label || record.colorType || '默认'
          }}
        </template>
        <template v-else-if="column.key === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
        <template v-else-if="column.key === 'action'">
          <div class="dict-actions">
            <a-button v-if="canUpdate" type="link" :disabled="!typeEnabled" @click="edit(record)">
              <EditOutlined />
              编辑
            </a-button>
            <a-popconfirm
              v-if="canUpdate"
              :disabled="!typeEnabled"
              :title="`确定${record.status === 0 ? '禁用' : '启用'}该字典数据吗？`"
              ok-text="确定"
              cancel-text="取消"
              @confirm="toggleStatus(record)"
            >
              <a-button type="link" :danger="record.status === 0" :disabled="!typeEnabled">
                <StopOutlined v-if="record.status === 0" />
                <CheckCircleOutlined v-else />
                {{ record.status === 0 ? '禁用' : '启用' }}
              </a-button>
            </a-popconfirm>
            <a-popconfirm
              v-if="canDelete"
              title="确定删除该字典数据吗？"
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
  </template>
  <OsModalForm v-bind="modalForm.modalProps.value" v-on="modalForm.modalEvents" :rules="formRules" width="620px">
    <template #formItems="{ formData }">
      <a-form-item label="所属字典">
        <a-input :value="`${dictType?.name || ''}（${formData.dictType}）`" disabled />
      </a-form-item>
      <a-form-item label="字典标签" name="label">
        <a-input v-model:value="formData.label" placeholder="显示给用户的名称" :maxlength="100" />
      </a-form-item>
      <a-form-item label="字典值" name="value" extra="同一字典内不可重复，作为业务存储值。">
        <a-input v-model:value="formData.value" placeholder="请输入字典值" :maxlength="100" />
      </a-form-item>
      <a-form-item label="显示顺序" name="sort" extra="数值越小越靠前，相同顺序按创建编号排列。">
        <a-input-number v-model:value="formData.sort" :min="0" :max="2147483647" :precision="0" />
      </a-form-item>
      <a-form-item label="状态" name="status">
        <a-radio-group v-model:value="formData.status" :options="DICT_STATUS_OPTIONS" />
      </a-form-item>
      <a-form-item label="颜色类型" name="colorType">
        <a-select v-model:value="formData.colorType" :options="DICT_COLOR_OPTIONS" />
      </a-form-item>
      <a-form-item label="标签预览">
        <a-tag :color="dictTagColor(formData.colorType)">{{ formData.label || '字典标签' }}</a-tag>
      </a-form-item>
      <a-form-item label="样式类名" name="cssClass">
        <a-input v-model:value="formData.cssClass" placeholder="可选，供业务页面使用的 CSS 类名" :maxlength="100" />
      </a-form-item>
      <a-form-item label="备注" name="remark">
        <a-textarea v-model:value="formData.remark" :maxlength="500" :rows="3" show-count placeholder="请输入备注" />
      </a-form-item>
    </template>
  </OsModalForm>
</template>
