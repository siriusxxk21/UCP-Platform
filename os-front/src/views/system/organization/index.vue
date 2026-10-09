<script setup lang="ts">
import {computed, reactive, ref} from 'vue'
import type {TableColumnType} from 'ant-design-vue'
import {message, Modal} from 'ant-design-vue'
import {
  DeleteOutlined,
  EditOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import {useOsTablePage} from '@/composables/useOsTablePage'
import type {Organization, OrganizationFormData} from '@/api/system/organization'
import {
  createOrganization,
  deleteOrganization,
  getOrganizationTree,
  updateOrganization,
  updateOrganizationStatus,
} from '@/api/system/organization'

type OrgQueryForm = { orgName?: string; status?: number }

// ===== useOsTablePage =====

const {
  loading,
  tableData,
  queryForm,
  handleQuery,
  handleReset,
  fetchData,
} = useOsTablePage<Organization, OrgQueryForm>({
  fetchFn: (params) => getOrganizationTree({ orgName: params.orgName, status: params.status }),
  defaultQuery: () => ({
    orgName: undefined,
    status: undefined,
  }),
})

// ===== 树形表格展开状态 =====

const expandedRowKeys = ref<string[]>([])
const expandableConfig = computed(() => ({ childrenColumnName: 'children' }))

function getAllIds(data: Organization[]): string[] {
  const ids: string[] = []
  const traverse = (list: Organization[]) => {
    list.forEach(item => {
      if (item.children && item.children.length > 0) {
        ids.push(String(item.id))
        traverse(item.children)
      }
    })
  }
  traverse(data)
  return ids
}

// 数据加载后默认展开所有
function afterFetch(data: Organization[]): Organization[] {
  expandedRowKeys.value = getAllIds(data)
  return data
}

function handleExpand(_expanded: boolean, record: Organization) {
  const currentKeys = [...expandedRowKeys.value]
  const id = String(record.id)
  if (currentKeys.includes(id)) {
    currentKeys.splice(currentKeys.indexOf(id), 1)
  } else {
    currentKeys.push(id)
  }
  expandedRowKeys.value = currentKeys
}

function handleExpandAll() {
  expandedRowKeys.value = getAllIds(tableData.value)
}

function handleCollapseAll() {
  expandedRowKeys.value = []
}

// ===== 列定义 =====

const columns: TableColumnType[] = [
  // 文本列使用固定表格布局与省略显示，避免长名称/编码撑宽整张表。
  { title: '组织名称', dataIndex: 'orgName', key: 'orgName', width: 220, ellipsis: true, align: 'left' },
  { title: '组织编码', dataIndex: 'orgCode', key: 'orgCode', width: 100, ellipsis: true },
  { title: '类型', dataIndex: 'orgType', key: 'orgType', width: 70, align: 'center' },
  { title: '负责人', dataIndex: 'leaderName', key: 'leaderName', width: 80, ellipsis: true },
  { title: '联系电话', dataIndex: 'phone', key: 'phone', width: 100, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 70, align: 'center' },
  { title: '操作', key: 'action', width: 200, align: 'center' },
]

function orgTypeLabel(type: number) {
  const map: Record<number, string> = { 1: '公司', 2: '分公司', 3: '部门' }
  return map[type] || '未知'
}

function orgTypeColor(type: number) {
  const map: Record<number, string> = { 1: 'blue', 2: 'cyan', 3: 'green' }
  return map[type] || 'default'
}

// ===== 新增 / 编辑弹窗 =====

const modalVisible = ref(false)
const modalLoading = ref(false)
const modalTitle = ref('新增组织')
const formRef = ref()

const formData = reactive<OrganizationFormData & { id?: string }>({
  orgCode: '',
  orgName: '',
  orgType: 2,
  parentId: undefined,
  phone: '',
  email: '',
  address: '',
  sortOrder: 0,
  status: 1,
})

const formRules = {
  orgCode: [{ required: true, message: '请输入组织编码', trigger: 'blur' }],
  orgName: [{ required: true, message: '请输入组织名称', trigger: 'blur' }],
  orgType: [{ required: true, message: '请选择组织类型', trigger: 'change' }],
}

function resetForm() {
  formData.id = undefined
  formData.orgCode = ''
  formData.orgName = ''
  formData.orgType = 2
  formData.parentId = undefined
  formData.phone = ''
  formData.email = ''
  formData.address = ''
  formData.sortOrder = 0
  formData.status = 1
}

function handleAdd(parent?: Organization) {
  resetForm()
  if (parent && parent.id) {
    formData.parentId = String(parent.id)
  }
  modalTitle.value = '新增组织'
  modalVisible.value = true
}

function handleEdit(record: Organization) {
  formData.id = record.id
  formData.orgCode = record.orgCode
  formData.orgName = record.orgName
  formData.orgType = record.orgType
  formData.parentId = record.parentId || undefined
  formData.phone = record.phone || ''
  formData.email = record.email || ''
  formData.address = record.address || ''
  formData.sortOrder = record.sortOrder || 0
  formData.status = record.status
  modalTitle.value = '编辑组织'
  modalVisible.value = true
}

function handleDelete(record: Organization) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除组织 "${record.orgName}" 吗？删除后子节点将一并删除。`,
    okType: 'danger',
    onOk: async () => {
      try {
        await deleteOrganization(record.id)
        message.success('删除成功')
        fetchData()
      } catch (e) {
        console.error('删除失败', e)
      }
    },
  })
}

async function handleStatusChange(record: Organization, checked: boolean) {
  try {
    await updateOrganizationStatus(record.id, checked ? 1 : 0)
    message.success('状态更新成功')
    record.status = checked ? 1 : 0
  } catch (e) {
    console.error('状态更新失败', e)
  }
}

function handleModalOk() {
  formRef.value.validate().then(async () => {
    modalLoading.value = true
    try {
      if (formData.id) {
        await updateOrganization(formData.id, { ...formData })
        message.success('编辑成功')
      } else {
        await createOrganization({ ...formData })
        message.success('新增成功')
      }
      modalVisible.value = false
      fetchData()
    } catch (e) {
      console.error('保存失败', e)
    } finally {
      modalLoading.value = false
    }
  })
}

function handleModalCancel() {
  modalVisible.value = false
  formRef.value?.resetFields()
}
</script>

<template>
  <div class="org-manage">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="false"
      :show-index="false"
      row-key="id"
      title="组织列表"
      :scroll="{ y: 'calc(100vh - 360px)' }"
      resizable
      :expandable="expandableConfig"
      :expanded-row-keys="expandedRowKeys"
      @expand="handleExpand"
    >
      <!-- 搜索区域 -->
      <template #search>
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="组织名称">
            <a-input v-model:value="queryForm.orgName" placeholder="请输入组织名称" style="width: 180px" @press-enter="handleQuery" />
          </a-form-item>
          <a-form-item label="状态">
            <a-select v-model:value="queryForm.status" style="width: 180px" allow-clear placeholder="请选择" @change="handleQuery">
              <a-select-option :value="1">启用</a-select-option>
              <a-select-option :value="0">禁用</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="handleQuery">
                <SearchOutlined />查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <!-- 工具栏按钮 -->
      <template #toolbar>
        <a-button @click="handleExpandAll">
          <NodeExpandOutlined />展开全部
        </a-button>
        <a-button @click="handleCollapseAll">
          <NodeCollapseOutlined />折叠全部
        </a-button>
      </template>

      <!-- 新增按钮 -->
      <template #actions>
        <a-button type="primary" @click="handleAdd()">
          <PlusOutlined />新增
        </a-button>
      </template>

      <!-- 单元格渲染 -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'orgType'">
          <a-tag :color="orgTypeColor((record as Organization).orgType)">
            {{ (record as Organization).orgTypeName || orgTypeLabel((record as Organization).orgType) }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-switch
            :checked="(record as Organization).status === 1"
            checked-children="启用"
            un-checked-children="禁用"
            @change="(checked: boolean) => handleStatusChange(record as Organization, checked)"
          />
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <a-button type="link" style="color: #6b7280; padding: 0 6px" @click="handleAdd(record as Organization)">
              <PlusOutlined />子节点
            </a-button>
            <span class="action-sep">|</span>
            <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="handleEdit(record as Organization)">
              <EditOutlined />编辑
            </a-button>
            <span class="action-sep">|</span>
            <a-button type="link" danger style="padding: 0 6px; font-weight: 500" @click="handleDelete(record as Organization)">
              <DeleteOutlined />删除
            </a-button>
          </div>
        </template>
      </template>
    </OsTablePage>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="modalTitle"
      width="600px"
      :confirm-loading="modalLoading"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form ref="formRef" :model="formData" :rules="formRules" :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
        <a-form-item label="上级组织" name="parentId">
          <a-tree-select
            v-model:value="formData.parentId"
            :tree-data="tableData"
            :field-names="{ label: 'orgName', value: 'id', children: 'children' }"
            placeholder="请选择上级组织（不选则为顶级）"
            allow-clear
            tree-default-expand-all
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="组织编码" name="orgCode">
          <a-input v-model:value="formData.orgCode" placeholder="请输入组织编码" />
        </a-form-item>
        <a-form-item label="组织名称" name="orgName">
          <a-input v-model:value="formData.orgName" placeholder="请输入组织名称" />
        </a-form-item>
        <a-form-item label="组织类型" name="orgType">
          <a-select v-model:value="formData.orgType" placeholder="请选择组织类型">
            <a-select-option :value="1">公司</a-select-option>
            <a-select-option :value="2">分公司</a-select-option>
            <a-select-option :value="3">部门</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="联系电话" name="phone">
          <a-input v-model:value="formData.phone" placeholder="请输入联系电话" />
        </a-form-item>
        <a-form-item label="电子邮箱" name="email">
          <a-input v-model:value="formData.email" placeholder="请输入电子邮箱" />
        </a-form-item>
        <a-form-item label="地址" name="address">
          <a-textarea v-model:value="formData.address" placeholder="请输入地址" :rows="2" />
        </a-form-item>
        <a-form-item label="排序" name="sortOrder">
          <a-input-number v-model:value="formData.sortOrder" :min="0" :max="9999" style="width: 100%" />
        </a-form-item>
        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formData.status">
            <a-radio :value="1">启用</a-radio>
            <a-radio :value="0">禁用</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.org-manage {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.action-cell {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.action-sep {
  color: #d1d5db;
  padding: 0 6px;
  user-select: none;
}

.org-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.org-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
