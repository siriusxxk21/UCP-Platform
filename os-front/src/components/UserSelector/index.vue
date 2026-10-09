<script setup lang="ts">
//文档地址http://10.8.0.7:15433/s/9d772273-fced-4d66-86e8-53b45405124e
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import { DeleteOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { getOrgDeptTree } from '@/api/system/organization'
import type { User } from '@/api/system/user'
import { getUserList } from '@/api/system/user'
import type { OrgDeptTreeNode } from '@/utils/system/orgDeptTree'
import { isSelectableUser } from './userSelection'

interface Props {
  visible: boolean
  /** 初始选择模式：true=多选，false=单选，默认 true */
  multiple?: boolean
  /** 是否显示多选/单选切换开关，默认 true */
  showMultipleToggle?: boolean
  selectedUsers?: User[]
  /** 可选候选用户范围；传入后仍复用系统分页、组织和搜索能力。 */
  candidateUserIds?: Array<string | number>
  /** 仅允许选择启用用户；默认关闭以兼容角色、消息等既有调用。 */
  enabledOnly?: boolean
  title?: string
  width?: number | string
}

const props = withDefaults(defineProps<Props>(), {
  multiple: true,
  showMultipleToggle: true,
  selectedUsers: () => [],
  title: '选择人员',
  width: 1200
})

const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
  (e: 'confirm', users: User[]): void
  (e: 'cancel'): void
}>()

// ============================== 接口与类型 ==============================

type TreeNode = OrgDeptTreeNode
interface RawOrgDeptNode {
  id?: string | number
  rawId?: string | number
  name?: string
  orgName?: string
  deptName?: string
  label?: string
  nodeType?: string
  orgId?: string | number
  children?: RawOrgDeptNode[]
}

// 头像颜色调色板（与 MemberSelect 一致）
const AVATAR_COLORS = [
  '#7C3AED',
  '#2563EB',
  '#059669',
  '#D97706',
  '#DC2626',
  '#0891B2',
  '#4F46E5',
  '#0D9488',
  '#C2410C',
  '#B91C1C',
  '#4338CA',
  '#15803D',
  '#B45309',
  '#BE185D',
  '#0E7490',
  '#6D28D9',
  '#1D4ED8'
]

function hashColor(id: string | number): string {
  const s = String(id)
  let hash = 0
  for (let i = 0; i < s.length; i += 1) {
    hash = (hash << 5) - hash + s.charCodeAt(i)
    hash |= 0
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]
}

function avatarColor(user: User): string {
  return hashColor(user.id)
}

function avatarInitial(user: User): string {
  return (user.nickname || user.username || '?').charAt(0).toUpperCase()
}

// ============================== 状态定义 ==============================

const orgDeptTree = ref<TreeNode[]>([])
const deptLoading = ref(false)
const selectedOrgId = ref<string>('')
const selectedDeptId = ref<string>('')
const deptKeywords = ref('')

const userList = ref<User[]>([])
const userLoading = ref(false)
const userKeywords = ref('')
const userUsername = ref('')
const pagination = reactive({
  current: 1,
  pageSize: DEFAULT_PAGE_SIZE,
  total: 0
})

const internalSelectedUsers = ref<User[]>([])

// 内部多选状态（可通过开关切换）
const internalMultiple = ref(props.multiple)

// ============================== 模糊搜索过滤混合树 ==============================

const filteredTree = computed(() => {
  const tree = orgDeptTree.value
  if (!Array.isArray(tree) || tree.length === 0) return []

  if (!deptKeywords.value) return tree

  const keyword = deptKeywords.value.trim().toLowerCase()

  function filterNode(node: TreeNode): TreeNode | null {
    if (!node) return null
    const isMatch = node.name?.toLowerCase().includes(keyword) ?? false

    let filteredChildren: TreeNode[] = []
    if (Array.isArray(node.children)) {
      filteredChildren = node.children.map(filterNode).filter((c): c is TreeNode => c !== null)
    }

    if (isMatch || filteredChildren.length > 0) {
      return {
        ...node,
        children: filteredChildren,
        isLeaf: filteredChildren.length === 0
      }
    }
    return null
  }

  return tree.map(filterNode).filter((n): n is TreeNode => n !== null)
})

// ============================== 树数据转换 ==============================

/** 将 API 返回的组织部门混合树转换为组件 TreeNode 格式 */
function transformOrgDeptTree(data: RawOrgDeptNode[], parentPath: string = ''): TreeNode[] {
  if (!Array.isArray(data)) return []
  return data.map((item, index) => {
    // 后端已通过 nodeType 字段区分 org/dept，直接使用即可
    const nodeType: 'org' | 'dept' = item.nodeType === 'dept' ? 'dept' : 'org'
    // 使用 rawId（不含前缀的原始ID），避免前缀重复叠加
    const rawId = String(item.rawId ?? item.id ?? index)
    const key = nodeType === 'org' ? `org_${rawId}` : `dept_${rawId}`
    const nodeName = item.name || item.orgName || item.deptName || item.label || `未命名-${index}`

    const currentPath = parentPath ? `${parentPath} / ${nodeName}` : nodeName

    return {
      id: key,
      key,
      rawId,
      name: nodeName,
      nodeType,
      orgId: item.orgId ? String(item.orgId) : undefined,
      children: item.children?.length ? transformOrgDeptTree(item.children, currentPath) : undefined,
      isLeaf: !item.children || item.children.length === 0
    }
  })
}

// ============================== 数据获取 ==============================

// 获取组织与部门混合树 (后端一次性返回)
async function fetchDeptTree() {
  deptLoading.value = true
  try {
    const res = await getOrgDeptTree()
    orgDeptTree.value = transformOrgDeptTree(Array.isArray(res) ? res : [])
  } finally {
    deptLoading.value = false
  }
}

// 获取用户列表
async function fetchUserList() {
  if (props.candidateUserIds && props.candidateUserIds.length === 0) {
    userList.value = []
    pagination.total = 0
    return
  }
  userLoading.value = true
  try {
    const keyword = userKeywords.value?.trim()
    const username = userUsername.value?.trim()

    // 服务端搜索
    const res = await getUserList({
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      keyword: keyword || undefined,
      username: username || undefined,
      deptId: selectedDeptId.value || undefined,
      orgId: selectedOrgId.value || undefined,
      ids: props.candidateUserIds,
      status: props.enabledOnly ? 0 : undefined
    })
    userList.value = Array.isArray(res?.list) ? res.list : []
    pagination.total = Number(res?.total || 0)
  } finally {
    userLoading.value = false
  }
}

// ============================== 事件处理 ==============================

// 树节点选中变更
function handleTreeSelect(keys: string[]) {
  const selectedKey = keys[0]
  if (selectedKey) {
    if (selectedKey.startsWith('org_')) {
      selectedOrgId.value = selectedKey.substring(4)
      selectedDeptId.value = ''
    } else if (selectedKey.startsWith('dept_')) {
      selectedOrgId.value = ''
      selectedDeptId.value = selectedKey.substring(5)
    }
  } else {
    selectedOrgId.value = ''
    selectedDeptId.value = ''
  }
  pagination.current = 1
  fetchUserList()
}

// 搜索用户
function handleUserSearch() {
  pagination.current = 1
  fetchUserList()
}

// 重置搜索条件
function handleReset() {
  userKeywords.value = ''
  userUsername.value = ''
  deptKeywords.value = ''
  selectedOrgId.value = ''
  selectedDeptId.value = ''
  pagination.current = 1
  fetchDeptTree()
  fetchUserList()
}

// 当前页是否全部选中
const isCurrentPageAllSelected = computed(() => {
  const selectable = userList.value.filter(selectableUser)
  if (!internalMultiple.value || selectable.length === 0) return false
  const selectedIds = new Set(internalSelectedUsers.value.map(u => u.id))
  return selectable.every(u => selectedIds.has(u.id))
})

function selectableUser(user: User) {
  return isSelectableUser(user, props.enabledOnly)
}

// 表格选中变更（复选框/单选框）
function onSelectChange(_selectedRowKeys: string[], selectedRows: User[]) {
  const allowedRows = selectedRows.filter(selectableUser)
  if (internalMultiple.value) {
    // 保留其他页的选中项
    const otherPageSelected = internalSelectedUsers.value.filter(u => !userList.value.some(curr => curr.id === u.id))
    internalSelectedUsers.value = [...otherPageSelected, ...allowedRows]
  } else {
    internalSelectedUsers.value = allowedRows.slice(0, 1)
  }
}

// 行点击处理
const customRow = (record: User) => ({
  onClick: () => {
    if (!selectableUser(record)) return
    const userId = record.id
    if (internalMultiple.value) {
      const index = internalSelectedUsers.value.findIndex(u => u.id === userId)
      if (index >= 0) {
        internalSelectedUsers.value = internalSelectedUsers.value.filter(u => u.id !== userId)
      } else {
        internalSelectedUsers.value = [...internalSelectedUsers.value, record]
      }
    } else {
      internalSelectedUsers.value = [record]
    }
  }
})

// 移除已选人员
function removeSelected(userId: string) {
  internalSelectedUsers.value = internalSelectedUsers.value.filter(u => u.id !== userId)
}

// 清空已选
function clearAll() {
  internalSelectedUsers.value = []
}

// 选中/取消选中当前页全部人员
function toggleSelectAllCurrentPage() {
  if (!internalMultiple.value) return
  if (isCurrentPageAllSelected.value) {
    // 取消全选：移除当前页所有项
    const currentPageIds = new Set(userList.value.filter(selectableUser).map(u => u.id))
    internalSelectedUsers.value = internalSelectedUsers.value.filter(u => !currentPageIds.has(u.id))
  } else {
    // 全选：添加当前页未选中项
    const alreadySelectedIds = internalSelectedUsers.value.map(u => u.id)
    const toAdd = userList.value.filter(user => selectableUser(user) && !alreadySelectedIds.includes(user.id))
    internalSelectedUsers.value = [...internalSelectedUsers.value, ...toAdd]
  }
}

// 提交
function handleConfirm() {
  const invalid = internalSelectedUsers.value.filter(user => !selectableUser(user))
  if (invalid.length) {
    message.error(`已选人员中有 ${invalid.length} 个账号已停用，请移除后再确认`)
    return
  }
  emit('confirm', [...internalSelectedUsers.value])
  emit('update:visible', false)
}

// 取消
function handleCancel() {
  emit('update:visible', false)
  emit('cancel')
}

// ============================== 生命周期与监听 ==============================

function syncInternalSelectedUsers(users = props.selectedUsers) {
  internalSelectedUsers.value = Array.isArray(users) ? users.filter(Boolean) : []
}

watch(
  () => props.visible,
  val => {
    if (val) {
      internalMultiple.value = props.multiple
      syncInternalSelectedUsers()
      fetchDeptTree()
      fetchUserList()
    }
  },
  { immediate: true }
)

watch(
  () => props.selectedUsers,
  users => {
    if (props.visible) syncInternalSelectedUsers(users)
  },
  { deep: true, flush: 'sync' }
)

const selectedRowKeys = computed(() => internalSelectedUsers.value.map(u => u.id))

const tableColumns = [
  { title: '用户名', dataIndex: 'username', key: 'username', width: 200 },
  { title: '姓名', dataIndex: 'nickname', key: 'nickname', width: 180 },
  { title: '部门', dataIndex: 'deptName', key: 'deptName' }
]
</script>

<template>
  <a-modal
    :open="visible"
    :title="title"
    :width="width"
    :body-style="{ padding: '0' }"
    @update:open="$emit('update:visible', $event)"
    @ok="handleConfirm"
    @cancel="handleCancel"
  >
    <div class="user-selector-container">
      <!-- 左侧：组织部门混合树 -->
      <div class="dept-sidebar">
        <div class="sidebar-search">
          <a-input v-model:value="deptKeywords" placeholder="请输入组织或部门名称" allow-clear>
            <template #prefix>
              <SearchOutlined />
            </template>
          </a-input>
        </div>
        <div class="dept-tree-wrapper">
          <a-tree
            v-if="filteredTree.length"
            :key="deptKeywords"
            :tree-data="filteredTree"
            :field-names="{ title: 'name', key: 'id', children: 'children' }"
            default-expand-all
            block-node
            @select="(keys: (string | number)[]) => handleTreeSelect(keys.map(String))"
          >
            <template #title="node">
              <span v-if="node" class="tree-node-title">
                <span v-if="node.nodeType === 'org'" class="node-tag org-tag">组织</span>
                <span v-else-if="node.nodeType === 'dept'" class="node-tag dept-tag">部门</span>
                <span class="node-name" :title="node.name">{{ node.name }}</span>
              </span>
            </template>
          </a-tree>
          <!--          <a-empty v-else-if="!deptLoading" :description="deptKeywords ? '未搜索到结果' : '暂无数据'" />-->
          <!--          <a-empty v-if="!internalSelectedUsers.length" description="暂未选择" />-->
        </div>
      </div>

      <!-- 中间：用户列表 -->
      <div class="user-main">
        <div class="user-search-bar">
          <span class="label">用户名</span>
          <a-input
            v-model:value="userUsername"
            placeholder="用户名/昵称/拼音"
            style="width: 130px"
            allow-clear
            @press-enter="handleUserSearch"
          />
          <span class="label">姓名</span>
          <a-input
            v-model:value="userKeywords"
            placeholder="姓名/拼音"
            style="width: 160px"
            allow-clear
            @press-enter="handleUserSearch"
          />
          <a-button type="primary" size="small" @click="handleUserSearch">查询</a-button>
          <a-button size="small" @click="handleReset">重置</a-button>
          <a-button
            v-if="internalMultiple"
            :danger="isCurrentPageAllSelected"
            size="small"
            @click="toggleSelectAllCurrentPage"
          >
            {{ isCurrentPageAllSelected ? '取消全选' : '选中全部' }}
          </a-button>
        </div>

        <div class="user-table-wrapper">
          <a-table
            :columns="tableColumns"
            :data-source="userList"
            row-key="id"
            :loading="userLoading"
            :pagination="{
              ...pagination,
              showSizeChanger: true,
              showTotal: (total: number) => `共 ${total} 条`
            }"
            :row-selection="{
              selectedRowKeys,
              onChange: onSelectChange,
              type: internalMultiple ? 'checkbox' : 'radio',
              getCheckboxProps: (record: User) => ({ disabled: !selectableUser(record) })
            }"
            :custom-row="customRow"
            size="middle"
            @change="
              (p: { current?: number; pageSize?: number }) => {
                pagination.current = p.current || 1
                pagination.pageSize = p.pageSize || DEFAULT_PAGE_SIZE
                fetchUserList()
              }
            "
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'username'">
                <div class="user-cell">
                  <div class="user-avatar" :style="{ background: avatarColor(record) }">
                    <img v-if="record.avatar" :src="record.avatar" :alt="record.nickname || record.username" />
                    <template v-else>{{ avatarInitial(record) }}</template>
                  </div>
                  <span class="user-cell-text">{{ record.username }}</span>
                  <a-tag v-if="enabledOnly && !selectableUser(record)" color="red">已停用</a-tag>
                </div>
              </template>
            </template>
          </a-table>
        </div>
      </div>

      <!-- 右侧：已选人员 -->
      <div class="selected-sidebar">
        <div class="selected-header">
          <span class="title">已选人员({{ internalSelectedUsers.length }})</span>
          <a-button type="link" size="small" danger @click="clearAll">清空</a-button>
        </div>
        <div class="selected-list">
          <div
            v-for="user in internalSelectedUsers"
            :key="user.id"
            class="selected-item"
            :class="{ 'selected-item--invalid': !selectableUser(user) }"
          >
            <span class="user-name">{{ user.nickname || user.username }}</span>
            <a-tag v-if="!selectableUser(user)" color="red">已停用</a-tag>
            <DeleteOutlined class="remove-icon" @click="removeSelected(user.id)" />
          </div>
          <a-empty v-if="!internalSelectedUsers?.length" description="暂未选择" :image="false" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="selector-footer">
        <a-button @click="handleCancel">取消</a-button>
        <a-button type="primary" @click="handleConfirm">确认</a-button>
      </div>
    </template>
  </a-modal>
</template>

<style scoped>
.user-selector-container {
  display: grid;
  grid-template-columns: minmax(180px, 23%) minmax(0, 1fr) minmax(160px, 22%);
  height: 560px;
  background: #f9fafb;
}

/* 左侧部门 */
.dept-sidebar {
  min-width: 0;
  background: #fff;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
}

.sidebar-search {
  padding: 16px;
  border-bottom: 1px solid #f3f4f6;
}

.dept-tree-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

/* 中间主内容 */
.user-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px;
  background: #fff;
  min-width: 0;
}

.user-search-bar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.user-search-bar .label {
  color: #374151;
  font-weight: 500;
  white-space: nowrap;
  font-size: 13px;
}

.user-table-wrapper {
  flex: 1;
  overflow: auto;
  min-height: 0;
}

/* 右侧已选 */
.selected-sidebar {
  min-width: 0;
  background: #fff;
  border-left: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
}

.selected-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f3f4f6;
}

.selected-header .title {
  font-weight: 600;
  color: #1f2937;
}

.selected-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.selected-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-radius: 6px;
  margin-bottom: 4px;
  background: #fdf2f2;
  /* 浅红背景，匹配图片 */
  transition: all 0.2s;
}

.selected-item--invalid {
  border: 1px solid #ffccc7;
  background: #fff2f0;
}

.selected-item:hover {
  background: #fee2e2;
}

.user-name {
  color: #4b5563;
  font-size: 14px;
  overflow-wrap: anywhere;
}
@media (max-width: 760px) {
  .user-selector-container {
    grid-template-columns: minmax(130px, 28%) minmax(0, 1fr);
    height: 65vh;
  }
  .selected-sidebar {
    grid-column: 1 / -1;
    max-height: 140px;
    border-top: 1px solid #e5e7eb;
  }
  .user-main,
  .sidebar-search,
  .selected-header {
    padding: 8px;
  }
}

.remove-icon {
  color: #ef4444;
  cursor: pointer;
  font-size: 14px;
  opacity: 0.7;
}

.remove-icon:hover {
  opacity: 1;
}

.selector-footer {
  padding: 12px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 消除 Ant Design Modal 默认 footer 内边距与上边框 */
:deep(.ant-modal-footer) {
  padding: 0;
  margin-top: 0;
  border-top: none;
}

/* 消除 .ant-modal-content 默认大内边距导致的上下/右侧留白 */
:deep(.ant-modal-content) {
  padding: 0;
}

:deep(.ant-table-wrapper) {
  height: 100%;
}

:deep(.ant-spin-nested-loading) {
  height: 100%;
}

:deep(.ant-table) {
  border-radius: 8px;
}

/* 组织与部门徽标样式 */
.tree-node-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  user-select: none;
  max-width: 100%;
  min-width: 0;
}

.node-tag {
  flex-shrink: 0;
  white-space: nowrap;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  font-weight: 600;
  line-height: 1.2;
}

.org-tag {
  background: #eff6ff;
  color: #3b82f6;
  border: 1px solid #bfdbfe;
  font-size: 12px;
  font-weight: 500;
}

.dept-tag {
  background: #f0fdf4;
  color: #22c55e;
  border: 1px solid #bbf7d0;
  font-size: 12px;
  font-weight: 500;
}

.node-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  color: #374151;
  font-size: 14px;
  font-weight: 500;
}

/* 用户头像 */
.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-avatar {
  width: 24px;
  height: 24px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  overflow: hidden;
}

.user-cell-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
