<script setup lang="ts">
import { useCompactViewport } from '@/composables/useCompactViewport'
import { computed, onMounted, ref } from 'vue'
import { userFormRules } from './user-form-rules'
import type { TableColumnType } from 'ant-design-vue'
import { Empty, message } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useOsModalForm } from '@/composables/useOsModalForm'
import type { DynamicSearchField } from '@/components/ucp-table-page/types'
import type { CreateUserParams, UpdateUserParams, User, UserQueryParams } from '@/api/system/user'
import {
  batchDeleteUser,
  createUser,
  deleteUser,
  downloadUserTemplate,
  exportUser,
  getUserList,
  importUser,
  resetUserPassword,
  updateUser,
  updateUserStatus
} from '@/api/system/user'
import type { Department } from '@/api/system/department'
import { getDepartmentTree } from '@/api/system/department'
import { getOrgDeptTree } from '@/api/system/organization'
import { transformOrgDeptTree } from '@/utils/system/orgDeptTree'
import type { OrgDeptTreeNode } from '@/utils/system/orgDeptTree'
import { toPinyin, toPinyinInitials } from '@/utils/pinyin'
import { today, triggerDownload } from '@/utils/file'
import { formatDateTime } from '@/utils/format'

// ===== 类型（从 API 类型派生，单一真相源）=====

type UserQueryForm = Pick<UserQueryParams, 'username' | 'mobile' | 'status' | 'orgId' | 'deptId'>

// ===== 枚举常量（一处定义，多处引用）=====

const compactViewport = useCompactViewport('(max-width: 1023px)')
const mobileFiltersOpen = ref(false)

const STATUS_OPTIONS = [
  { label: '启用', value: 0 },
  { label: '禁用', value: 1 }
]

const SEX_OPTIONS = [
  { label: '男', value: 1 },
  { label: '女', value: 2 },
  { label: '未知', value: 0 }
]

// ===== 表格列定义（不含序号列，OsTablePage 自动注入）=====

const columns: TableColumnType[] = [
  { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 120 },
  { title: '所属部门', dataIndex: 'deptName', key: 'deptName', width: 140, ellipsis: true },
  { title: '手机号', dataIndex: 'mobile', key: 'mobile', width: 130 },
  { title: '邮箱', dataIndex: 'email', key: 'email', width: 180, ellipsis: true },
  { title: '性别', dataIndex: 'sex', key: 'sex', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 80 },
  { title: '最后登录IP', dataIndex: 'loginIp', key: 'loginIp', width: 130 },
  { title: '最后登录时间', dataIndex: 'loginDate', key: 'loginDate', width: 170 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 280, fixed: 'right' }
]

// ===== 列枚举 Tag 映射（OsTablePage 自动渲染 a-tag）=====

const tagMap: Record<string, Record<string | number, { label: string; color?: string }>> = {
  sex: {
    1: { label: '男', color: 'blue' },
    2: { label: '女', color: 'magenta' },
    0: { label: '未知', color: 'default' }
  },
  status: {
    0: { label: '启用', color: 'success' },
    1: { label: '禁用', color: 'error' }
  }
}

// ===== 动态检索字段配置 =====

const dynamicSearchFields: DynamicSearchField[] = [
  { field: 'username', label: '用户名', type: 'text' },
  { field: 'nickname', label: '昵称', type: 'text' },
  { field: 'mobile', label: '手机号', type: 'text' },
  {
    field: 'status',
    label: '状态',
    type: 'select',
    operators: ['eq'],
    options: STATUS_OPTIONS
  },
  { field: 'createTime', label: '创建时间', type: 'datetimeRange' }
]

// ===== useOsTablePage — 表格核心状态 =====

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  selectedRows,
  handleQuery,
  handleReset: resetTableQuery,
  handleTableChange,
  fetchData,
  clearSelection,
  updateSelection,
  handleDynamicSearch
} = useOsTablePage<User, UserQueryForm>({
  fetchFn: params => getUserList(params as UserQueryParams),
  defaultQuery: () => ({
    username: undefined,
    mobile: undefined,
    status: undefined,
    orgId: undefined,
    deptId: undefined
  }),
  dataField: 'list'
})

// ===== 组织 / 部门树 =====

const deptTreeData = ref<Department[]>([])

// ===== 左侧组织 / 部门筛选树 =====

const orgDeptTreeData = ref<OrgDeptTreeNode[]>([])
const filteredOrgDeptTreeData = computed(() => {
  const keyword = treeKeyword.value.trim().toLowerCase()
  if (!keyword) return orgDeptTreeData.value

  function filterNode(node: OrgDeptTreeNode): OrgDeptTreeNode | null {
    const children = (node.children || []).map(filterNode).filter((item): item is OrgDeptTreeNode => item !== null)
    const searchableText = [node.name, toPinyin(node.name), toPinyinInitials(node.name)].join(' ').toLowerCase()
    if (searchableText.includes(keyword) || children.length > 0) {
      return { ...node, children: children.length > 0 ? children : undefined, isLeaf: children.length === 0 }
    }
    return null
  }

  return orgDeptTreeData.value.map(filterNode).filter((item): item is OrgDeptTreeNode => item !== null)
})
const orgDeptTreeLoading = ref(false)
const orgDeptTreeError = ref(false)
const treeKeyword = ref('')
const selectedTreeKeys = ref<string[]>([])

async function loadOrgDeptTree() {
  orgDeptTreeLoading.value = true
  orgDeptTreeError.value = false
  try {
    const result = await getOrgDeptTree()
    orgDeptTreeData.value = transformOrgDeptTree(Array.isArray(result) ? result : [])
  } catch {
    orgDeptTreeData.value = []
    orgDeptTreeError.value = true
  } finally {
    orgDeptTreeLoading.value = false
  }
}

function handleOrgDeptTreeSelect(keys: string[]) {
  const selectedKey = keys[0]
  selectedTreeKeys.value = selectedKey ? [selectedKey] : []
  queryForm.orgId = undefined
  queryForm.deptId = undefined
  if (selectedKey?.startsWith('org_')) {
    queryForm.orgId = selectedKey.slice(4)
  } else if (selectedKey?.startsWith('dept_')) {
    queryForm.deptId = selectedKey.slice(5)
  }
  pagination.current = 1
  fetchData()
}

function handleReset() {
  selectedTreeKeys.value = []
  treeKeyword.value = ''
  resetTableQuery()
}

async function loadDeptTree() {
  try {
    deptTreeData.value = (await getDepartmentTree()) || []
  } catch {
    deptTreeData.value = []
  }
}

// ===== 新增 / 编辑弹窗（useOsModalForm）=====

// 表单类型：从 API CreateUserParams 派生，确保字段同源
// username、status 必填（表单默认值保证），id 仅编辑时有值
interface UserForm extends Partial<Omit<CreateUserParams, 'username' | 'status'>> {
  id?: string
  username: string
  status: number
}

const modalForm = useOsModalForm<UserForm>({
  defaultDisplayMode: 'modal',
  displayModes: ['modal', 'drawer', 'fullscreen'],
  resizable: true,
  createFn: async data => {
    const id = (await createUser(data)) as string | number | undefined
    if (id && data.status !== undefined && data.status !== 0) await updateUserStatus(String(id), data.status)
  },
  updateFn: async data => {
    const { password, status, ...updateData } = data
    await updateUser(updateData as UpdateUserParams)
    if (data.id && status !== undefined) await updateUserStatus(String(data.id), status)
  },
  emptyFields: ['nickname', 'mobile', 'email', 'remark', 'password'],
  defaultForm: () => ({
    id: undefined,
    username: '',
    nickname: '',
    password: '',
    mobile: '',
    email: '',
    deptId: undefined,
    postIds: [],
    remark: '',
    sex: 0,
    status: 0
  }),
  afterSuccess: () => fetchData(),
  titles: { add: '新增用户', edit: '编辑用户' },
  afterOpenAdd: () => {
    loadDeptTree()
  },
  afterOpenEdit: () => {
    loadDeptTree()
  }
})

// 动态表单校验规则：编辑时不校验密码
// 注意：此 computed 必须在 modalForm 声明之后
// 规则见 user-form-rules.ts：昵称新增 / 编辑都必填（库里是非空列）
const formRules = computed(() => userFormRules(modalForm.isEdit.value))

function handleAdd() {
  modalForm.openAdd()
}

function handleEdit(record: User) {
  modalForm.openEdit(record as unknown as UserForm)
}

function formatUserDate(record: User, key: 'loginDate' | 'createTime') {
  return formatDateTime(record[key] || '')
}

// ===== 删除 =====

async function handleDelete(record: User) {
  try {
    await deleteUser(record.id)
    message.success('删除成功')
    fetchData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

// ===== 批量删除 =====

async function handleBatchDelete(keys: (string | number)[], _rows: User[]) {
  try {
    await batchDeleteUser(keys.map(k => String(k)))
    message.success(`已删除 ${keys.length} 条记录`)
    clearSelection()
    fetchData()
  } catch (error) {
    console.error('批量删除失败:', error)
  }
}

// ===== 状态切换（启用 / 禁用）=====

async function handleToggleStatus(record: User) {
  const newStatus = record.status === 0 ? 1 : 0
  const label = newStatus === 0 ? '启用' : '禁用'
  try {
    await updateUserStatus(record.id, newStatus)
    message.success(`${label}成功`)
    fetchData()
  } catch (error) {
    console.error('状态修改失败:', error)
  }
}

// ===== 重置密码弹窗 =====

const resetPwdVisible = ref(false)
const resetPwdLoading = ref(false)
const resetPwdFormRef = ref()
const resetPwdTargetId = ref<string>()
const resetPwdForm = reactive({ newPassword: '' })

const resetPwdRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不少于 6 位', trigger: 'blur' }
  ]
}

function handleResetPassword(record: User) {
  resetPwdTargetId.value = record.id
  resetPwdForm.newPassword = ''
  resetPwdVisible.value = true
}

async function handleResetPwdOk() {
  try {
    await resetPwdFormRef.value?.validate()
  } catch {
    return
  }
  resetPwdLoading.value = true
  try {
    await resetUserPassword(resetPwdTargetId.value!, resetPwdForm.newPassword)
    message.success('密码重置成功')
    resetPwdVisible.value = false
  } catch (error) {
    console.error('重置密码失败:', error)
  } finally {
    resetPwdLoading.value = false
  }
}

// ===== 导出 =====

async function handleExport() {
  try {
    const params = selectedRowKeys.value.length > 0 ? { ids: selectedRowKeys.value } : undefined
    const blob = await exportUser(params)
    triggerDownload(blob as Blob, `用户列表_${today()}.xlsx`)
    message.success('导出成功')
  } catch (error) {
    console.error('导出失败:', error)
  }
}

// ===== 下载模板 =====

async function handleDownloadTemplate() {
  try {
    const blob = await downloadUserTemplate()
    triggerDownload(blob as Blob, '用户导入模板.xlsx')
  } catch (error) {
    console.error('下载模板失败:', error)
  }
}

// ===== 导入 =====

async function handleImport(file: File) {
  try {
    await importUser(file)
    message.success('导入成功')
    fetchData()
  } catch (error) {
    console.error('导入失败:', error)
  }
}

// ===== 生命周期 =====

onMounted(() => {
  // 部门树仅用于弹窗，异步加载，不阻塞表格渲染
  loadDeptTree()
  loadOrgDeptTree()
})
</script>

<template>
  <div class="user-manage">
    <a-button
      v-if="compactViewport"
      class="mobile-panel-toggle"
      :aria-expanded="mobileFiltersOpen"
      @click="mobileFiltersOpen = !mobileFiltersOpen"
    >
      {{ mobileFiltersOpen ? '收起组织部门' : '筛选组织部门' }}
    </a-button>
    <div class="org-dept-sidebar" v-show="!compactViewport || mobileFiltersOpen">
      <div class="sidebar-header">
        <span class="sidebar-title">组织部门</span>
        <a-button type="text" size="small" :loading="orgDeptTreeLoading" @click="loadOrgDeptTree">
          <ReloadOutlined />
        </a-button>
      </div>
      <div class="sidebar-search">
        <a-input v-model:value="treeKeyword" placeholder="搜索组织或部门" allow-clear>
          <template #prefix><SearchOutlined /></template>
        </a-input>
      </div>
      <div class="sidebar-tree">
        <a-spin :spinning="orgDeptTreeLoading">
          <a-tree
            v-if="filteredOrgDeptTreeData.length > 0"
            v-model:selected-keys="selectedTreeKeys"
            :tree-data="filteredOrgDeptTreeData"
            :field-names="{ title: 'name', key: 'id', children: 'children' }"
            default-expand-all
            block-node
            @select="handleOrgDeptTreeSelect"
          >
            <template #title="node">
              <span class="tree-node-title">
                <span class="node-tag" :class="node.nodeType === 'org' ? 'org-tag' : 'dept-tag'">
                  {{ node.nodeType === 'org' ? '组织' : '部门' }}
                </span>
                <a-tooltip :title="node.name" placement="topLeft">
                  <span class="node-name">{{ node.name }}</span>
                </a-tooltip>
              </span>
            </template>
          </a-tree>
          <a-empty v-else-if="orgDeptTreeError" description="组织部门加载失败" :image="Empty.PRESENTED_IMAGE_SIMPLE" />
          <a-empty v-else description="暂无组织部门数据" :image="Empty.PRESENTED_IMAGE_SIMPLE" />
        </a-spin>
      </div>
    </div>

    <div class="user-list-content">
      <OsTablePage
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        title="用户列表"
        :scroll="{ x: 'max-content', y: 'calc(100vh - 350px)' }"
        :row-selection="true"
        :selected-row-keys="selectedRowKeys"
        :selected-rows="selectedRows as any[]"
        show-export
        show-import
        show-download-template
        show-advanced-search
        advanced-search-mode="dynamic"
        :dynamic-search-fields="dynamicSearchFields"
        search-storage-key="user-list"
        column-settings-key="user-list"
        show-column-settings
        resizable
        batch-delete-text="批量删除"
        :tag-map="tagMap"
        @change="handleTableChange"
        @selection-change="(keys, rows) => updateSelection(keys, rows as User[])"
        @batch-delete="(keys, rows) => handleBatchDelete(keys, rows as User[])"
        @export="handleExport"
        @import="handleImport"
        @download-template="handleDownloadTemplate"
        @dynamic-search="handleDynamicSearch"
        @search="handleQuery"
      >
        <!-- 基础搜索区域 -->
        <!-- triggerSearch：OsTablePage 提供的普通查询入口，会自动清除高级查询状态再触发查询 -->
        <template #search="{ triggerSearch }">
          <a-form layout="inline" :model="queryForm">
            <a-form-item label="用户名">
              <a-input
                v-model:value="queryForm.username"
                placeholder="用户名/昵称/拼音"
                allow-clear
                style="width: 150px"
                @press-enter="triggerSearch"
              />
            </a-form-item>
            <a-form-item label="手机号">
              <a-input
                v-model:value="queryForm.mobile"
                placeholder="请输入手机号"
                allow-clear
                style="width: 150px"
                @press-enter="triggerSearch"
              />
            </a-form-item>
            <a-form-item label="状态">
              <a-select v-model:value="queryForm.status" placeholder="全部" allow-clear style="width: 120px">
                <a-select-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </a-select-option>
              </a-select>
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

        <!-- 批量操作扩展区（配合批量操作栏使用）-->
        <template #batchActions>
          <!-- 此处可扩展批量操作，如批量启用/禁用等 -->
        </template>

        <!-- 操作按钮区：新增 -->
        <template #actions>
          <a-button type="primary" @click="handleAdd">
            <PlusOutlined />
            新增
          </a-button>
        </template>

        <!-- 单元格自定义渲染（仅操作列等业务特有渲染，枚举列由 tagMap 自动处理）-->
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'loginDate' || column.key === 'createTime'">
            {{ formatUserDate(record as User, column.key as 'loginDate' | 'createTime') }}
          </template>
          <!-- 操作列 -->
          <template v-else-if="column.key === 'action'">
            <template v-for="user in [record as User]" :key="user.id">
              <div class="action-cell">
                <!-- 编辑 -->
                <a-button
                  type="link"
                  style="color: #4338ca; padding: 0 6px; font-weight: 500"
                  @click="handleEdit(user)"
                >
                  <EditOutlined />
                  编辑
                </a-button>

                <span class="action-sep">|</span>

                <!-- 启用 / 禁用切换 -->
                <a-popconfirm
                  :title="`确定要${user.status === 0 ? '禁用' : '启用'}该用户吗？`"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="handleToggleStatus(user)"
                >
                  <a-button type="link" style="padding: 0 6px" :danger="user.status === 0">
                    <StopOutlined v-if="user.status === 0" />
                    <CheckCircleOutlined v-else />
                    {{ user.status === 0 ? '禁用' : '启用' }}
                  </a-button>
                </a-popconfirm>

                <span class="action-sep">|</span>

                <!-- 重置密码 -->
                <a-button type="link" style="color: #6b7280; padding: 0 6px" @click="handleResetPassword(user)">
                  <KeyOutlined />
                  重置密码
                </a-button>

                <span class="action-sep">|</span>

                <!-- 删除 -->
                <a-popconfirm
                  title="确定要删除该用户吗？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="handleDelete(user)"
                >
                  <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
                    <DeleteOutlined />
                    删除
                  </a-button>
                </a-popconfirm>
              </div>
            </template>
          </template>
        </template>
      </OsTablePage>
    </div>

    <!-- ===== 新增 / 编辑弹窗 ===== -->
    <OsModalForm v-bind="modalForm.modalProps.value" v-on="modalForm.modalEvents" :rules="formRules" width="620px">
      <template #formItems="{ formData }">
        <a-form-item label="用户名" name="username">
          <a-input
            v-model:value="formData.username"
            placeholder="请输入用户名"
            :disabled="modalForm.isEdit.value"
            autocomplete="off"
          />
        </a-form-item>

        <a-form-item label="昵称" name="nickname">
          <a-input v-model:value="formData.nickname" placeholder="请输入昵称" />
        </a-form-item>

        <a-form-item v-if="!modalForm.isEdit.value" label="密码" name="password">
          <a-input-password v-model:value="formData.password" placeholder="请输入密码" autocomplete="new-password" />
        </a-form-item>

        <a-form-item label="所属部门" name="deptId">
          <a-tree-select
            v-model:value="formData.deptId"
            :tree-data="deptTreeData"
            :field-names="{ label: 'deptName', value: 'id', children: 'children' }"
            placeholder="请选择所属部门"
            allow-clear
            tree-default-expand-all
            :list-height="512"
            style="width: 100%"
          />
        </a-form-item>

        <a-form-item label="手机号" name="mobile">
          <a-input v-model:value="formData.mobile" placeholder="请输入手机号" />
        </a-form-item>

        <a-form-item label="邮箱" name="email">
          <a-input v-model:value="formData.email" placeholder="请输入邮箱" />
        </a-form-item>

        <a-form-item label="性别" name="sex">
          <a-select v-model:value="formData.sex" placeholder="请选择性别">
            <a-select-option v-for="opt in SEX_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="formData.remark" placeholder="请输入备注" />
        </a-form-item>

        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formData.status">
            <a-radio v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</a-radio>
          </a-radio-group>
        </a-form-item>
      </template>
    </OsModalForm>

    <!-- ===== 重置密码弹窗 ===== -->
    <a-modal
      v-model:open="resetPwdVisible"
      title="重置密码"
      width="420px"
      :confirm-loading="resetPwdLoading"
      @ok="handleResetPwdOk"
      @cancel="resetPwdVisible = false"
    >
      <a-form
        ref="resetPwdFormRef"
        :model="resetPwdForm"
        :rules="resetPwdRules"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 16 }"
      >
        <a-form-item label="新密码" name="newPassword">
          <a-input-password v-model:value="resetPwdForm.newPassword" placeholder="请输入新密码（至少 6 位）" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.user-manage {
  height: 100%;
  display: flex;
  flex-direction: row;
  gap: 16px;
  min-height: 0;
}

.org-dept-sidebar {
  width: 240px;
  flex: 0 0 240px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
  padding: 12px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.sidebar-title {
  font-weight: 600;
  color: #111827;
}

.sidebar-search {
  margin-bottom: 10px;
}

.sidebar-tree {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
}

.sidebar-tree :deep(.ant-tree) {
  width: 100%;
  overflow-x: hidden;
}

/* 收紧组织部门树的层级缩进，让更多层级在固定宽度内完整展示。 */
.sidebar-tree :deep(.ant-tree-indent-unit) {
  width: 16px;
}

.sidebar-tree :deep(.ant-tree-switcher) {
  width: 20px;
}

.sidebar-tree :deep(.ant-tree-treenode),
.sidebar-tree :deep(.ant-tree-node-content-wrapper),
.sidebar-tree :deep(.ant-tree-title) {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.sidebar-tree :deep(.ant-tree-title) {
  display: block;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-node-title {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 6px;
}

.node-tag {
  flex: 0 0 auto;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
}

.org-tag {
  color: #1d4ed8;
  background: #dbeafe;
}

.dept-tag {
  color: #047857;
  background: #d1fae5;
}

.node-name {
  min-width: 0;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-node-title :deep(.ant-tooltip-trigger) {
  min-width: 0;
  overflow: hidden;
}

.user-list-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
}

/* 用户筛选条件保持单行，避免搜索区换行挤压列表高度；空间不足时仅横向滚动。 */
.user-list-content :deep(.os-table-page__search-basic) {
  min-width: max-content;
  overflow-x: auto;
}

.user-list-content :deep(.os-table-page__search-form) {
  min-width: max-content;
}

.user-list-content :deep(.os-table-page__search-form .ant-form-inline) {
  flex-wrap: nowrap;
  gap: 0 8px;
  white-space: nowrap;
}

.user-list-content :deep(.os-table-page__search-form .ant-form-item) {
  flex: 0 0 auto;
  margin-inline-end: 0;
}

/* 分页保持在内容卡片底部，表格区域优先占用剩余高度以完整展示每页数据。 */
.user-list-content :deep(.os-table-page__table .ant-pagination) {
  margin: 4px 0 0;
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

.user-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.user-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}

@media (max-width: 1023px) {
  .user-list-content :deep(.os-table-page__search-basic),
  .user-list-content :deep(.os-table-page__search-form) {
    min-width: 0;
  }
  .user-list-content :deep(.os-table-page__search-form .ant-form-inline) {
    flex-wrap: wrap;
    white-space: normal;
  }
  .user-list-content :deep(.os-table-page__search-form .ant-form-item) {
    flex: 1 1 150px;
  }
  .user-manage {
    flex-direction: column;
    gap: 10px;
    padding: 0;
    height: auto;
    min-height: 100%;
  }
  .user-manage .org-dept-sidebar {
    width: 100%;
    flex: none;
    max-height: 240px;
    overflow: auto;
    box-sizing: border-box;
  }
  .user-manage .user-list-content {
    width: 100%;
    min-width: 0;
    flex: 1 0 auto;
  }
  .mobile-panel-toggle {
    flex: none;
    min-height: 40px;
    align-self: flex-start;
  }
}
</style>
