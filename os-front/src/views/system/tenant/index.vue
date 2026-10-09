<script setup lang="ts">
import {computed, reactive, ref} from 'vue'
import type {TableColumnType} from 'ant-design-vue'
import {message, Modal} from 'ant-design-vue'
import {
  CheckOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import {useOsTablePage} from '@/composables/useOsTablePage'
import type {Tenant, TenantFormData, TenantQueryParams} from '@/api/system/tenant'
import {createTenant, deleteTenant, getTenantList, updateTenant, updateTenantStatus,} from '@/api/system/tenant'

type TenantQueryForm = Pick<TenantQueryParams, 'tenantName' | 'tenantType' | 'status'>

// ===== useOsTablePage — 表格核心状态 =====

const {
  loading,
  tableData,
  pagination,
  queryForm,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData,
} = useOsTablePage<Tenant, TenantQueryForm>({
  fetchFn: (params) => getTenantList(params as TenantQueryParams),
  defaultQuery: () => ({
    tenantName: undefined,
    tenantType: undefined,
    status: undefined,
  }),
  dataField: 'records',
})

// ===== 列定义 =====

const columns: TableColumnType[] = [
  { title: '租户编码', dataIndex: 'tenantCode', key: 'tenantCode', width: 130 },
  { title: '租户名称', dataIndex: 'tenantName', key: 'tenantName' },
  { title: '类型', dataIndex: 'tenantType', key: 'tenantType', width: 110, align: 'center' },
  { title: '联系人', dataIndex: 'contactName', key: 'contactName', width: 100 },
  { title: '联系电话', dataIndex: 'contactPhone', key: 'contactPhone', width: 130 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90, align: 'center' },
  { title: '到期时间', dataIndex: 'expireTime', key: 'expireTime', width: 160 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 160 },
  { title: '操作', key: 'action', width: 220, align: 'center', fixed: 'right' },
]

// ===== 列枚举 Tag 映射 =====

const tagMap: Record<string, Record<string | number, { label: string; color?: string }>> = {
  tenantType: {
    1: { label: '标准租户', color: 'blue' },
    2: { label: '试用租户', color: 'orange' },
    3: { label: '内部租户', color: 'purple' },
  },
  status: {
    0: { label: '禁用', color: 'error' },
    1: { label: '正常', color: 'success' },
    2: { label: '已过期', color: 'warning' },
  },
}

// ===== 新增 / 编辑弹窗 =====

const modalVisible = ref(false)
const modalLoading = ref(false)
const modalTitle = ref('新增租户')
const formRef = ref()

const expireTimeValue = computed({
  get: () => formData.expireTime as any,
  set: (val: any) => { formData.expireTime = val || undefined },
})

const formData = reactive<TenantFormData & { id?: string }>({
  tenantCode: '',
  tenantName: '',
  tenantType: 1,
  contactName: '',
  contactPhone: '',
  contactEmail: '',
  domain: '',
  isolationStrategy: 'ROW',
  status: 1,
  expireTime: undefined,
  maxUserCount: undefined,
  adminUsername: '',
  adminPassword: '',
  adminNickname: '',
  adminPhone: '',
  adminEmail: '',
})

const formRules = {
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: 'blur' }],
  tenantName: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  tenantType: [{ required: true, message: '请选择租户类型', trigger: 'change' }],
  adminUsername: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  adminPassword: [{ required: true, message: '请输入管理员密码', trigger: 'blur' }],
}

function isExpired(expireTime?: string) {
  if (!expireTime) return false
  return new Date(expireTime) < new Date()
}

function resetForm() {
  formData.id = undefined
  formData.tenantCode = ''
  formData.tenantName = ''
  formData.tenantType = 1
  formData.contactName = ''
  formData.contactPhone = ''
  formData.contactEmail = ''
  formData.domain = ''
  formData.isolationStrategy = 'ROW'
  formData.status = 1
  formData.expireTime = undefined
  formData.maxUserCount = undefined
  formData.adminUsername = ''
  formData.adminPassword = ''
  formData.adminNickname = ''
  formData.adminPhone = ''
  formData.adminEmail = ''
}

function handleAdd() {
  resetForm()
  modalTitle.value = '新增租户'
  modalVisible.value = true
}

function handleEdit(record: Tenant) {
  formData.id = record.id
  formData.tenantCode = record.tenantCode
  formData.tenantName = record.tenantName
  formData.tenantType = record.tenantType
  formData.contactName = record.contactName || ''
  formData.contactPhone = record.contactPhone || ''
  formData.contactEmail = record.contactEmail || ''
  formData.domain = record.domain || ''
  formData.isolationStrategy = record.isolationStrategy || 'ROW'
  formData.status = record.status
  formData.expireTime = record.expireTime || undefined
  formData.maxUserCount = record.maxUserCount
  modalTitle.value = '编辑租户'
  modalVisible.value = true
}

function handleDelete(record: Tenant) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除租户 "${record.tenantName}" 吗？此操作不可恢复。`,
    okType: 'danger',
    onOk: async () => {
      try {
        await deleteTenant(record.id)
        message.success('删除成功')
        fetchData()
      } catch (e) {
        console.error('删除失败', e)
      }
    },
  })
}

async function handleToggleStatus(record: Tenant) {
  const newStatus = record.status === 1 ? 0 : 1
  const action = newStatus === 1 ? '启用' : '禁用'
  Modal.confirm({
    title: `确认${action}`,
    content: `确定要${action}租户 "${record.tenantName}" 吗？`,
    onOk: async () => {
      try {
        await updateTenantStatus(record.id, newStatus)
        message.success(`${action}成功`)
        fetchData()
      } catch (e) {
        console.error(`${action}失败`, e)
      }
    },
  })
}

function handleModalOk() {
  formRef.value.validate().then(async () => {
    modalLoading.value = true
    try {
      if (formData.id) {
        await updateTenant(formData.id, { ...formData })
        message.success('编辑成功')
      } else {
        await createTenant({ ...formData })
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
  <div class="tenant-manage">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      title="租户列表"
      :scroll="{ y: 'calc(100vh - 360px)' }"
      :tag-map="tagMap"
      resizable
      @change="handleTableChange"
    >
      <!-- 搜索区域 -->
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="租户名称">
            <a-input v-model:value="queryForm.tenantName" placeholder="请输入租户名称" allow-clear style="width: 180px" @press-enter="triggerSearch" />
          </a-form-item>
          <a-form-item label="租户类型">
            <a-select v-model:value="queryForm.tenantType" style="width: 180px" allow-clear placeholder="请选择">
              <a-select-option :value="1">标准租户</a-select-option>
              <a-select-option :value="2">试用租户</a-select-option>
              <a-select-option :value="3">内部租户</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="状态">
            <a-select v-model:value="queryForm.status" style="width: 180px" allow-clear placeholder="请选择">
              <a-select-option :value="1">正常</a-select-option>
              <a-select-option :value="0">禁用</a-select-option>
              <a-select-option :value="2">已过期</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <!-- 操作按钮 -->
      <template #actions>
        <a-button type="primary" @click="handleAdd">
          <PlusOutlined />新增租户
        </a-button>
      </template>

      <!-- 单元格渲染 -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'expireTime'">
          <span :class="{ 'expired-text': isExpired((record as Tenant).expireTime) }">
            {{ (record as Tenant).expireTime || '永久有效' }}
          </span>
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="handleEdit(record as Tenant)">
              <EditOutlined />编辑
            </a-button>
            <span class="action-sep">|</span>
            <a-button
              type="link"
              style="padding: 0 6px"
              :danger="(record as Tenant).status === 1"
              @click="handleToggleStatus(record as Tenant)"
            >
              <StopOutlined v-if="(record as Tenant).status === 1" />
              <CheckOutlined v-else />
              {{ (record as Tenant).status === 1 ? '禁用' : '启用' }}
            </a-button>
            <span class="action-sep">|</span>
            <a-button type="link" danger style="padding: 0 6px; font-weight: 500" @click="handleDelete(record as Tenant)">
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
      width="800px"
      :confirm-loading="modalLoading"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form ref="formRef" :model="formData" :rules="formRules" :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="租户编码" name="tenantCode" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.tenantCode" placeholder="请输入租户编码" :disabled="!!formData.id" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="租户名称" name="tenantName" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.tenantName" placeholder="请输入租户名称" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="租户类型" name="tenantType" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-select v-model:value="formData.tenantType" placeholder="请选择">
                <a-select-option :value="1">标准租户</a-select-option>
                <a-select-option :value="2">试用租户</a-select-option>
                <a-select-option :value="3">内部租户</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="隔离策略" name="isolationStrategy" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-select v-model:value="formData.isolationStrategy" placeholder="请选择" allow-clear>
                <a-select-option value="ROW">行级隔离</a-select-option>
                <a-select-option value="SCHEMA">Schema隔离</a-select-option>
                <a-select-option value="DB">数据库隔离</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="联系人" name="contactName" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.contactName" placeholder="请输入联系人" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="联系电话" name="contactPhone" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.contactPhone" placeholder="请输入联系电话" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="联系邮箱" name="contactEmail" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.contactEmail" placeholder="请输入联系邮箱" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="域名" name="domain" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input v-model:value="formData.domain" placeholder="如 example.com" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="最大用户数" name="maxUserCount" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-input-number v-model:value="formData.maxUserCount" :min="1" style="width: 100%" placeholder="不限填0" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="到期时间" name="expireTime" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-date-picker
                v-model:value="expireTimeValue"
                style="width: 100%"
                placeholder="不选为永久有效"
                value-format="YYYY-MM-DD HH:mm:ss"
                show-time
                allow-clear
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="状态" name="status" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
              <a-radio-group v-model:value="formData.status">
                <a-radio :value="1">正常</a-radio>
                <a-radio :value="0">禁用</a-radio>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>

        <template v-if="!formData.id">
          <a-divider>管理员信息</a-divider>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="管理员账号" name="adminUsername" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
                <a-input v-model:value="formData.adminUsername" placeholder="请输入管理员账号" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="管理员密码" name="adminPassword" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
                <a-input-password v-model:value="formData.adminPassword" placeholder="请输入管理员密码" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="管理员昵称" name="adminNickname" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
                <a-input v-model:value="formData.adminNickname" placeholder="默认为'管理员'" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="管理员手机" name="adminPhone" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
                <a-input v-model:value="formData.adminPhone" placeholder="选填" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="管理员邮箱" name="adminEmail" :label-col="{ span: 8 }" :wrapper-col="{ span: 14 }">
                <a-input v-model:value="formData.adminEmail" placeholder="选填" />
              </a-form-item>
            </a-col>
          </a-row>
        </template>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.tenant-manage {
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

.tenant-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.tenant-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}

.expired-text {
  color: #ff4d4f;
  font-weight: 500;
}

</style>
