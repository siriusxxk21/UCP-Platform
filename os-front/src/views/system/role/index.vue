<script setup lang="ts">
import {reactive, ref} from 'vue'
import type {TableColumnType} from 'ant-design-vue'
import {message, Modal} from 'ant-design-vue'
import {
  BorderOutlined,
  CheckSquareOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import {useOsTablePage} from '@/composables/useOsTablePage'
import type {Role, RoleQueryParams, User} from '@/api/system/role'
import {
  assignRoleUsers,
  batchDeleteRole,
  createRole,
  deleteRole,
  getRoleList,
  getRoleMenus,
  getRoleUsers,
  toggleRoleGlobalProjectView,
  updateRole,
  updateRoleMenus,
} from '@/api/system/role'
import {getMenuTree} from '@/api/system/menu'
import {formatDateTime} from '@/utils/format'
import UserSelector from '@/components/UserSelector/index.vue'

// ===== 类型 =====

const MENU_TYPE = {
  DIRECTORY: 1,
  MENU: 2,
  BUTTON: 3,
} as const

function getMenuTypeColor(type?: number) {
  switch (type) {
    case MENU_TYPE.DIRECTORY: return 'blue'
    case MENU_TYPE.MENU: return 'green'
    case MENU_TYPE.BUTTON: return 'orange'
    default: return 'default'
  }
}

function getMenuTypeText(type?: number) {
  switch (type) {
    case MENU_TYPE.DIRECTORY: return '目录'
    case MENU_TYPE.MENU: return '菜单'
    case MENU_TYPE.BUTTON: return '按钮'
    default: return '未知'
  }
}

interface RoleQueryForm {
  roleName?: string
  status?: number
}

// ===== 表格列定义（不含序号列，OsTablePage 自动注入）=====

const columns: TableColumnType[] = [
  { title: '角色名称', dataIndex: 'name', key: 'name', width: 140 },
  { title: '角色编码', dataIndex: 'code', key: 'code', width: 140 },
  { title: '排序', dataIndex: 'sort', key: 'sort', width: 80 },
  { title: '备注', dataIndex: 'remark', key: 'remark', width: 200, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 80 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 280, fixed: 'right' },
]

// ===== 列枚举 Tag 映射（OsTablePage 自动渲染 a-tag）=====

const tagMap: Record<string, Record<string | number, { label: string; color?: string }>> = {
  status: {
    0: { label: '启用', color: 'success' },
    1: { label: '禁用', color: 'error' },
  },
}

// ===== useOsTablePage — 表格核心状态 =====

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  selectedRows,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData,
  clearSelection,
  updateSelection,
} = useOsTablePage<Role, RoleQueryForm>({
  fetchFn: (params) => getRoleList(params as RoleQueryParams),
  defaultQuery: () => ({
    roleName: undefined,
    status: undefined,
  }),
  dataField: 'list',
})

// ===== 新增 / 编辑弹窗 =====

const modalVisible = ref(false)
const modalTitle = ref('新增角色')
const formRef = ref()

const formData = reactive({
  id: undefined as string | undefined,
  name: '',
  code: '',
  sort: 0,
  remark: '',
  status: 0,
  globalProjectView: 0,
})

const formRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  sort: [{ required: true, message: '请输入排序', trigger: 'blur' }],
}

/** 当前编辑角色的类型：1=系统内置 2=自定义 */
const currentRoleType = ref(2)

function handleAdd() {
  modalTitle.value = '新增角色'
  currentRoleType.value = 2
  formData.id = undefined
  formData.name = ''
  formData.code = ''
  formData.sort = 0
  formData.remark = ''
  formData.status = 0
  formData.globalProjectView = 0
  modalVisible.value = true
}

function handleEdit(record: Role) {
  modalTitle.value = '编辑角色'
  currentRoleType.value = record.type ?? 2
  formData.id = record.id
  formData.name = record.name
  formData.code = record.code
  formData.sort = record.sort ?? 0
  formData.remark = record.remark || ''
  formData.status = record.status
  formData.globalProjectView = record.globalProjectView ?? 0
  modalVisible.value = true
}

function handleModalOk() {
  // 系统内置角色：跳过表单校验，只切换全局项目访问权
  if (currentRoleType.value === 1) {
    toggleRoleGlobalProjectView(formData.id!, formData.globalProjectView ? 1 : 0)
      .then(() => {
        message.success('项目访问权设置成功')
        modalVisible.value = false
        fetchData()
      })
      .catch((error) => {
        console.error('设置失败:', error)
      })
    return
  }

  formRef.value.validate().then(async () => {
    try {
      if (modalTitle.value === '新增角色') {
        await createRole({
          name: formData.name,
          code: formData.code,
          sort: formData.sort,
          remark: formData.remark,
          status: formData.status,
          globalProjectView: formData.globalProjectView ? 1 : 0,
        })
        message.success('新增成功')
      }
      else {
        await updateRole({
          id: formData.id!,
          name: formData.name,
          code: formData.code,
          sort: formData.sort,
          remark: formData.remark,
          status: formData.status,
          globalProjectView: formData.globalProjectView ? 1 : 0,
        })
        message.success('编辑成功')
      }
      modalVisible.value = false
      fetchData()
    }
    catch (error) {
      console.error('保存失败:', error)
    }
  })
}

function handleModalCancel() {
  modalVisible.value = false
  formRef.value?.resetFields()
}

// ===== 删除 =====

function handleDelete(record: Role) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除角色 "${record.name}" 吗？`,
    onOk: async () => {
      try {
        await deleteRole(record.id)
        message.success('删除成功')
        fetchData()
      }
      catch (error) {
        console.error('删除失败:', error)
      }
    },
  })
}

// ===== 批量删除 =====

async function handleBatchDelete(keys: (string | number)[], _rows: Role[]) {
  try {
    await batchDeleteRole(keys.map(String))
    message.success(`已删除 ${keys.length} 条记录`)
    clearSelection()
    fetchData()
  }
  catch (error) {
    console.error('批量删除失败:', error)
  }
}

// ===== 权限配置弹窗 =====

const permissionVisible = ref(false)
const permissionLoading = ref(false)
const currentRoleId = ref<string>('')
const checkedKeys = ref<string[]>([])
const treeExpandedKeys = ref<string[]>([])
const menuTreeData = ref<any[]>([])
const checkStrictly = ref(true) // 父子联动开关，默认开启

// 构建节点映射（id -> 节点信息）
function buildNodeMap(tree: any[]): Map<string, any> {
  const map = new Map<string, any>()
  const traverse = (nodes: any[], parentId: string | null = null) => {
    nodes.forEach((node) => {
      const nodeId = String(node.id)
      map.set(nodeId, { ...node, id: nodeId, parentId })
      if (node.children && node.children.length > 0) {
        traverse(node.children, nodeId)
      }
    })
  }
  traverse(tree)
  return map
}

// 获取所有子孙节点ID
function getDescendantIds(nodeId: string, nodeMap: Map<string, any>): string[] {
  const ids: string[] = []
  nodeMap.forEach((node, id) => {
    if (node.parentId === nodeId) {
      ids.push(id)
      ids.push(...getDescendantIds(id, nodeMap))
    }
  })
  return ids
}

// 获取所有祖先节点ID
function getAncestorIds(nodeId: string, nodeMap: Map<string, any>): string[] {
  const ids: string[] = []
  const node = nodeMap.get(nodeId)
  if (node && node.parentId !== null && node.parentId !== '0') {
    ids.push(node.parentId)
    ids.push(...getAncestorIds(node.parentId, nodeMap))
  }
  return ids
}

// 自定义勾选处理（父子联动逻辑）
function handleTreeCheck(checked: { checked: string[], halfChecked: string[] }, e: any) {
  if (!checkStrictly.value) {
    checkedKeys.value = checked.checked.map(String)
    return
  }

  const nodeMap = buildNodeMap(menuTreeData.value)
  const newCheckedKeys = new Set(checked.checked.map(String))
  const checkedNodeId = String(e.node.id)
  const isChecked = e.checked

  if (isChecked) {
    const descendantIds = getDescendantIds(checkedNodeId, nodeMap)
    descendantIds.forEach(id => newCheckedKeys.add(id))
    const ancestorIds = getAncestorIds(checkedNodeId, nodeMap)
    ancestorIds.forEach(id => newCheckedKeys.add(id))
  }
  else {
    const descendantIds = getDescendantIds(checkedNodeId, nodeMap)
    descendantIds.forEach(id => newCheckedKeys.delete(id))
  }

  checkedKeys.value = Array.from(newCheckedKeys)
}

async function handlePermission(record: Role) {
  currentRoleId.value = record.id
  permissionLoading.value = true
  try {
    const [menuRes, roleMenus] = await Promise.all([
      getMenuTree(),
      getRoleMenus(record.id),
    ])
    menuTreeData.value = menuRes || []
    checkedKeys.value = (roleMenus || []).map(String)
    initExpandedKeys()
    permissionVisible.value = true
  }
  catch (error) {
    console.error('获取角色权限失败:', error)
    message.error('获取权限数据失败')
  }
  finally {
    permissionLoading.value = false
  }
}

// 展开全部
function handleExpandAll() {
  treeExpandedKeys.value = getAllIds(menuTreeData.value)
}

// 折叠全部
function handleCollapseAll() {
  treeExpandedKeys.value = []
}

// 全选
function handleCheckAll() {
  checkedKeys.value = getAllNodeIds(menuTreeData.value)
}

// 清空选择
function handleUncheckAll() {
  checkedKeys.value = []
}

// 获取所有节点ID
function getAllNodeIds(tree: any[]): string[] {
  const ids: string[] = []
  const traverse = (nodes: any[]) => {
    nodes.forEach((node) => {
      ids.push(String(node.id))
      if (node.children && node.children.length > 0) {
        traverse(node.children)
      }
    })
  }
  traverse(tree)
  return ids
}

// 获取所有有子节点的ID（用于展开）
function getAllIds(menus: any[]): string[] {
  const ids: string[] = []
  const traverse = (list: any[]) => {
    list.forEach((menu) => {
      if (menu.children && menu.children.length > 0) {
        ids.push(String(menu.id))
        traverse(menu.children)
      }
    })
  }
  traverse(menus)
  return ids
}

// 初始化展开状态
function initExpandedKeys() {
  treeExpandedKeys.value = getAllIds(menuTreeData.value)
}

async function handlePermissionOk() {
  if (!checkedKeys.value || checkedKeys.value.length === 0) {
    message.warning('请至少选择一个权限菜单')
    return
  }

  // 自动补齐所有勾选节点的祖先节点（确保父级目录不丢失）
  // 修复：取消父子联动时只勾选部分子菜单，保存后因父节点缺失导致子菜单被后端过滤掉
  if (checkedKeys.value.length > 0) {
    const nodeMap = buildNodeMap(menuTreeData.value)
    const completedKeys = new Set(checkedKeys.value)
    for (const key of checkedKeys.value) {
      const ancestorIds = getAncestorIds(key, nodeMap)
      ancestorIds.forEach(id => completedKeys.add(id))
    }
    checkedKeys.value = Array.from(completedKeys)
  }

  permissionLoading.value = true
  try {
    await updateRoleMenus(currentRoleId.value, checkedKeys.value)
    message.success('权限配置成功')
    permissionVisible.value = false
  }
  catch (error) {
    console.error('权限配置失败:', error)
    message.error('权限配置失败')
  }
  finally {
    permissionLoading.value = false
  }
}

function handlePermissionCancel() {
  permissionVisible.value = false
}

// ===== 分配用户弹窗 =====

const assignUserVisible = ref(false)
// 雪花 ID 超过 JavaScript 安全整数范围，必须以字符串参与比较和提交。
const assignUserOriginalIds = ref<string[]>([])
const assignUserPreSelectedUsers = ref<User[]>([])

function handleAssignUsers(record: Role) {
  currentRoleId.value = record.id
  loadAssignedUsers()
}

async function loadAssignedUsers() {
  try {
    const users = await getRoleUsers(currentRoleId.value)
    assignUserOriginalIds.value = (users || []).map(u => String(u.id))
    assignUserPreSelectedUsers.value = users || []
    assignUserVisible.value = true
  }
  catch (error) {
    console.error('加载角色用户失败:', error)
    message.error('加载用户数据失败')
  }
}

async function handleAssignUserConfirm(users: User[]) {
  const newIds = users.map(u => String(u.id))

  try {
    if (newIds.length !== assignUserOriginalIds.value.length
      || newIds.some(id => !assignUserOriginalIds.value.includes(id))) {
      await assignRoleUsers(currentRoleId.value, newIds)
      message.success('用户分配成功')
    }
    fetchData()
  }
  catch (error) {
    console.error('用户分配失败:', error)
    message.error('用户分配失败')
  }
}
</script>

<template>
  <div class="role-manage">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      title="角色列表"
      :row-selection="true"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="(selectedRows as any[])"
      :tag-map="tagMap"
      :show-batch-bar="true"
      batch-delete-text="批量删除"
      resizable
      :scroll="{ x: 'max-content', y: 'calc(100vh - 360px)' }"
      @change="handleTableChange"
      @selection-change="(keys, rows) => updateSelection(keys, rows as Role[])"
      @batch-delete="(keys, rows) => handleBatchDelete(keys, rows as Role[])"
    >
      <template #search>
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="角色名称">
            <a-input
              v-model:value="queryForm.roleName"
              placeholder="请输入角色名称"
              allow-clear
              style="width: 180px"
              @press-enter="handleQuery"
            />
          </a-form-item>
          <a-form-item label="状态">
            <a-select
              v-model:value="queryForm.status"
              placeholder="全部"
              allow-clear
              style="width: 180px"
            >
              <a-select-option :value="0">启用</a-select-option>
              <a-select-option :value="1">禁用</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="handleQuery">
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

      <!-- 操作按钮区：新增 -->
      <template #actions>
        <a-button type="primary" @click="handleAdd">
          <PlusOutlined />
          新增
        </a-button>
      </template>

      <!-- 单元格自定义渲染（仅操作列，状态列由 tagMap 自动处理）-->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">
          {{ formatDateTime((record as Role).createTime || '') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="handleEdit(record as Role)">
              <EditOutlined />
              编辑
            </a-button>
            <span class="action-sep">|</span>
            <a-button type="link" style="color: var(--brand); padding: 0 6px" @click="handlePermission(record as Role)">
              <KeyOutlined />
              权限
            </a-button>
            <span class="action-sep">|</span>
            <a-button type="link" style="color: #10B981; padding: 0 6px" @click="handleAssignUsers(record as Role)">
              <UserOutlined />
              分配用户
            </a-button>
            <span class="action-sep">|</span>
            <a-popconfirm
              :title="`确定要删除角色 '${(record as Role).name}' 吗？`"
              ok-text="确定"
              cancel-text="取消"
              @confirm="handleDelete(record as Role)"
            >
              <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
                <DeleteOutlined />
                删除
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </template>
    </OsTablePage>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="modalTitle"
      ok-text="确定"
      cancel-text="取消"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form ref="formRef" :model="formData" :rules="formRules">
        <a-form-item label="角色名称" name="name">
          <a-input v-model:value="formData.name" placeholder="请输入角色名称" />
        </a-form-item>
        <a-form-item label="角色编码" name="code">
          <a-input v-model:value="formData.code" placeholder="请输入角色编码" />
        </a-form-item>
        <a-form-item label="排序" name="sort">
          <a-input-number v-model:value="formData.sort" :min="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="formData.remark" placeholder="请输入备注" />
        </a-form-item>
        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formData.status">
            <a-radio :value="0">启用</a-radio>
            <a-radio :value="1">禁用</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="项目访问" name="globalProjectView">
          <a-checkbox :checked="formData.globalProjectView === 1" @change="(e: any) => formData.globalProjectView = e.target.checked ? 1 : 0">
            允许访问所有项目空间（全局只读）
          </a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 权限配置弹窗 -->
    <a-modal
      v-model:open="permissionVisible"
      title="配置权限"
      width="600px"
      ok-text="确定"
      cancel-text="取消"
      :confirm-loading="permissionLoading"
      @ok="handlePermissionOk"
      @cancel="handlePermissionCancel"
    >
      <a-spin :spinning="permissionLoading">
        <div class="permission-tree-wrapper">
          <div class="permission-toolbar">
            <a-space>
              <a-button @click="handleExpandAll">
                <NodeExpandOutlined /> 展开
              </a-button>
              <a-button @click="handleCollapseAll">
                <NodeCollapseOutlined /> 折叠
              </a-button>
              <a-button type="primary" @click="handleCheckAll">
                <CheckSquareOutlined /> 全选
              </a-button>
              <a-button @click="handleUncheckAll">
                <BorderOutlined /> 清空
              </a-button>
            </a-space>
            <a-divider type="vertical" />
            <a-checkbox v-model:checked="checkStrictly">
              父子联动
            </a-checkbox>
          </div>
          <div class="permission-tree-content">
            <a-tree
              v-model:checked-keys="checkedKeys"
              v-model:expanded-keys="treeExpandedKeys"
              checkable
              :check-strictly="true"
              :tree-data="menuTreeData"
              :field-names="{ title: 'name', key: 'id', children: 'children' }"
              class="menu-tree"
              @check="handleTreeCheck"
            >
              <template #title="{ data }">
                <span class="menu-node">
                  <span class="menu-name">{{ data.name }}</span>
                  <a-tag
                    :color="getMenuTypeColor(data.menuType)"
                    class="menu-tag"
                  >
                    {{ getMenuTypeText(data.menuType) }}
                  </a-tag>
                </span>
              </template>
            </a-tree>
          </div>
        </div>
      </a-spin>
    </a-modal>

    <!-- 分配用户弹窗 -->
    <UserSelector
      v-model:visible="assignUserVisible"
      :multiple="true"
      :selected-users="assignUserPreSelectedUsers"
      title="分配用户"
      @confirm="handleAssignUserConfirm"
    />
  </div>
</template>

<style scoped>
.role-manage {
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

.role-manage :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.role-manage :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}

.permission-tree-wrapper {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  overflow: hidden;
}

.permission-toolbar {
  padding: 12px 16px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.permission-toolbar :deep(.ant-divider-vertical) {
  margin: 0 4px;
}

.permission-tree-content {
  max-height: 400px;
  overflow-y: auto;
  padding: 8px 0;
}

.menu-tree :deep(.ant-tree-node-content-wrapper) {
  display: flex;
  align-items: center;
  padding: 4px 8px;
}

.menu-tree :deep(.ant-tree-node-content-wrapper-normal) {
  width: auto;
}

.menu-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-width: 200px;
}

.menu-name {
  font-size: 14px;
  color: #262626;
  margin-right: 12px;
}

.menu-tag {
  font-size: 12px;
  line-height: 18px;
  height: 20px;
  padding: 0 6px;
  flex-shrink: 0;
}

.menu-tree :deep(.ant-tree-checkbox) {
  margin-right: 4px;
}

.menu-tree :deep(.ant-tree-switcher) {
  width: 18px;
  height: 24px;
  line-height: 24px;
}

.menu-tree :deep(.ant-tree-indent-unit) {
  width: 24px;
}

.menu-tree :deep(.ant-tree-treenode) {
  padding: 2px 0;
}

.menu-tree :deep(.ant-tree-treenode:hover) {
  background-color: #f5f5f5;
}

.menu-tree :deep(.ant-tree-node-selected) {
  background-color: #e6f7ff !important;
}
</style>
