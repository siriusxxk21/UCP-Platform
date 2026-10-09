<script setup lang="ts">
import { useCompactViewport } from '@/composables/useCompactViewport'
import { computed, onMounted, reactive, ref } from 'vue'
import type { TableColumnType } from 'ant-design-vue'
import { Empty, message, Modal } from 'ant-design-vue'
import {
  DeleteOutlined,
  EditOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  TeamOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import type { Organization } from '@/api/system/organization'
import { getOrganizationTree } from '@/api/system/organization'
import type { Department } from '@/api/system/department'
import { deleteDepartment, getDepartmentTreeByOrgId, updateDepartmentStatus } from '@/api/system/department'
import DepartmentForm from './components/DepartmentForm.vue'
import DeptUserManage from './components/DeptUserManage.vue'

// 组织树
const compactViewport = useCompactViewport()
const mobileFiltersOpen = ref(false)

const orgTreeLoading = ref(false)
const orgTree = ref<Organization[]>([])
const orgList = ref<Organization[]>([])
const selectedOrgKeys = ref<string[]>([])
const selectedOrgId = ref<string | undefined>(undefined)

// 部门列表
const loading = ref(false)
const deptTree = ref<Department[]>([])
const expandedRowKeys = ref<string[]>([])
const queryForm = reactive({ deptName: '', status: undefined as number | undefined })
const statusUpdatingIds = ref<Set<string>>(new Set())
let latestDeptTreeRequestId = 0

// 树形表格配置
const expandableConfig = computed(() => ({ childrenColumnName: 'children' }))

// 部门表单弹窗
const formVisible = ref(false)
const editDept = ref<Department | null>(null)
const defaultParentId = ref<string | undefined>(undefined)

// 部门用户弹窗
const userModalVisible = ref(false)
const currentDept = ref<Department | null>(null)

const columns: TableColumnType[] = [
  // 左侧部门树会占用页面宽度；文本列固定宽度并省略，避免长内容撑出表格。
  { title: '部门名称', dataIndex: 'deptName', key: 'deptName', width: 165, ellipsis: true, align: 'left' },
  { title: '部门编码', dataIndex: 'deptCode', key: 'deptCode', width: 90, ellipsis: true },
  { title: '负责人', dataIndex: 'leaderName', key: 'leaderName', width: 70, ellipsis: true },
  { title: '联系电话', dataIndex: 'phone', key: 'phone', width: 90, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 70, align: 'center' },
  { title: '操作', key: 'action', width: 240, align: 'center' }
]

function triggerSearch() {
  void loadDeptTree()
}

// 加载组织树
async function loadOrgTree() {
  orgTreeLoading.value = true
  try {
    const tree = await getOrganizationTree()
    orgTree.value = tree || []
    orgList.value = flattenOrgs(tree || [])
  } catch (e) {
    console.error('加载组织树失败', e)
  } finally {
    orgTreeLoading.value = false
  }
}

function flattenOrgs(nodes: Organization[]): Organization[] {
  const result: Organization[] = []
  const traverse = (list: Organization[]) => {
    list.forEach(node => {
      result.push(node)
      if (node.children?.length) traverse(node.children)
    })
  }
  traverse(nodes)
  return result
}

// 加载部门树
async function loadDeptTree() {
  const requestId = ++latestDeptTreeRequestId
  if (!selectedOrgId.value) {
    deptTree.value = []
    expandedRowKeys.value = []
    return
  }
  const orgId = selectedOrgId.value
  const searchParams = {
    deptName: queryForm.deptName,
    status: queryForm.status
  }
  loading.value = true
  try {
    const data = await getDepartmentTreeByOrgId(orgId, searchParams)
    if (requestId !== latestDeptTreeRequestId || orgId !== selectedOrgId.value) return
    deptTree.value = data || []
    expandedRowKeys.value = getAllDeptIds(deptTree.value)
  } catch (e) {
    console.error('加载部门树失败', e)
  } finally {
    if (requestId === latestDeptTreeRequestId) loading.value = false
  }
}

function getAllDeptIds(data: Department[]): string[] {
  const ids: string[] = []
  const traverse = (list: Department[]) => {
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

// 展开/折叠
function handleExpand(_expanded: boolean, record: Department) {
  const currentKeys = [...expandedRowKeys.value]
  const id = String(record.id)
  const index = currentKeys.indexOf(id)
  if (index > -1) {
    currentKeys.splice(index, 1)
  } else {
    currentKeys.push(id)
  }
  expandedRowKeys.value = currentKeys
}

function handleExpandAll() {
  expandedRowKeys.value = getAllDeptIds(deptTree.value)
}

function handleCollapseAll() {
  expandedRowKeys.value = []
}

// 组织树选择
function handleOrgTreeSelect(keys: string[]) {
  if (keys.length > 0) {
    selectedOrgId.value = String(keys[0])
    selectedOrgKeys.value = [String(keys[0])]
  } else {
    selectedOrgId.value = undefined
    selectedOrgKeys.value = []
    deptTree.value = []
  }
  loadDeptTree()
}

function handleReset() {
  queryForm.deptName = ''
  queryForm.status = undefined
  void loadDeptTree()
}

// 新增部门
function handleAdd() {
  editDept.value = null
  defaultParentId.value = undefined
  formVisible.value = true
}

// 新增子部门
function handleAddChild(parent: Department) {
  editDept.value = null
  defaultParentId.value = parent.id
  formVisible.value = true
}

// 编辑部门
function handleEdit(record: Department) {
  editDept.value = record
  defaultParentId.value = undefined
  formVisible.value = true
}

// 删除部门
function handleDelete(record: Department) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除部门 "${record.deptName}" 吗？`,
    okType: 'danger',
    onOk: async () => {
      try {
        await deleteDepartment(record.id)
        message.success('删除成功')
        loadDeptTree()
      } catch (e) {
        console.error('删除失败', e)
      }
    }
  })
}

// 状态切换
async function handleStatusChange(record: Department, checked: boolean) {
  const recordId = String(record.id)
  statusUpdatingIds.value = new Set(statusUpdatingIds.value).add(recordId)
  try {
    await updateDepartmentStatus(record.id, checked ? 0 : 1)
    message.success('状态更新成功')
    await loadDeptTree()
  } catch (e) {
    console.error('状态更新失败', e)
  } finally {
    const updatingIds = new Set(statusUpdatingIds.value)
    updatingIds.delete(recordId)
    statusUpdatingIds.value = updatingIds
  }
}

// 部门用户管理
function handleManageUsers(dept: Department) {
  currentDept.value = dept
  userModalVisible.value = true
}

onMounted(async () => {
  await loadOrgTree()
})
</script>

<template>
  <div class="dept-manage">
    <a-button
      v-if="compactViewport"
      class="mobile-panel-toggle"
      :aria-expanded="mobileFiltersOpen"
      @click="mobileFiltersOpen = !mobileFiltersOpen"
    >
      {{ mobileFiltersOpen ? '收起组织架构' : '筛选组织架构' }}
    </a-button>
    <!-- 左侧组织树 -->
    <div class="dept-tree-panel" v-show="!compactViewport || mobileFiltersOpen">
      <div class="panel-header">
        <span class="panel-title">组织架构</span>
        <a-button type="text" @click="loadOrgTree">
          <ReloadOutlined />
        </a-button>
      </div>
      <div class="panel-body">
        <a-spin :spinning="orgTreeLoading">
          <a-tree
            v-if="orgTree.length > 0"
            v-model:selected-keys="selectedOrgKeys"
            :tree-data="orgTree"
            :field-names="{ title: 'orgName', key: 'id', children: 'children' }"
            :default-expand-all="true"
            @select="handleOrgTreeSelect"
          >
            <template #title="{ orgName, status }">
              <span :class="{ 'disabled-node': status === 0 }">{{ orgName }}</span>
            </template>
          </a-tree>
          <a-empty v-else description="暂无组织数据" :image="Empty.PRESENTED_IMAGE_SIMPLE" />
        </a-spin>
      </div>
    </div>

    <!-- 右侧内容区 -->
    <div class="dept-content-panel">
      <OsTablePage
        :columns="columns"
        :data-source="deptTree"
        :loading="loading"
        :pagination="false"
        :show-index="false"
        row-key="id"
        title="部门列表"
        :scroll="{ y: 'calc(100vh - 360px)' }"
        resizable
        :expandable="expandableConfig"
        :expanded-row-keys="expandedRowKeys"
        @expand="handleExpand"
      >
        <!-- 搜索区域 -->
        <template #search>
          <a-form layout="inline" :model="queryForm">
            <a-form-item label="部门名称">
              <a-input
                v-model:value="queryForm.deptName"
                allow-clear
                placeholder="请输入部门名称"
                style="width: 180px"
                @press-enter="triggerSearch"
              />
            </a-form-item>
            <a-form-item label="状态">
              <a-select
                v-model:value="queryForm.status"
                allow-clear
                placeholder="请选择"
                style="width: 180px"
                @change="triggerSearch"
              >
                <a-select-option :value="0">启用</a-select-option>
                <a-select-option :value="1">禁用</a-select-option>
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

        <!-- 工具栏按钮 -->
        <template #toolbar>
          <a-button @click="handleExpandAll">
            <NodeExpandOutlined />
            展开全部
          </a-button>
          <a-button @click="handleCollapseAll">
            <NodeCollapseOutlined />
            折叠全部
          </a-button>
        </template>

        <!-- 新增按钮 -->
        <template #actions>
          <a-button type="primary" @click="handleAdd">
            <PlusOutlined />
            新增
          </a-button>
        </template>

        <!-- 单元格渲染 -->
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-switch
              :checked="(record as Department).status === 0"
              checked-children="启用"
              :loading="statusUpdatingIds.has(String((record as Department).id))"
              un-checked-children="禁用"
              @change="(checked: boolean) => handleStatusChange(record as Department, checked)"
            />
          </template>
          <template v-else-if="column.key === 'deptCode'">
            {{ (record as Department).deptCode || '-' }}
          </template>
          <template v-else-if="column.key === 'action'">
            <div class="action-cell">
              <a-button
                style="color: #6b7280; padding: 0 3px"
                type="link"
                @click="handleManageUsers(record as Department)"
              >
                <TeamOutlined />
                用户
              </a-button>
              <span class="action-sep">|</span>
              <a-button
                style="color: #6b7280; padding: 0 3px"
                type="link"
                @click="handleAddChild(record as Department)"
              >
                <PlusOutlined />
                子部门
              </a-button>
              <span class="action-sep">|</span>
              <a-button
                style="color: #4338ca; padding: 0 3px; font-weight: 500"
                type="link"
                @click="handleEdit(record as Department)"
              >
                <EditOutlined />
                编辑
              </a-button>
              <span class="action-sep">|</span>
              <a-button
                danger
                style="padding: 0 3px; font-weight: 500"
                type="link"
                @click="handleDelete(record as Department)"
              >
                <DeleteOutlined />
                删除
              </a-button>
            </div>
          </template>
        </template>
      </OsTablePage>
    </div>

    <!-- 新增/编辑弹窗 -->
    <DepartmentForm
      v-model:visible="formVisible"
      :org-list="orgList"
      :current-org-id="selectedOrgId"
      :default-parent-id="defaultParentId"
      :edit-data="editDept"
      @success="loadDeptTree"
    />

    <!-- 部门用户管理弹窗 -->
    <DeptUserManage v-model:visible="userModalVisible" :dept="currentDept" />
  </div>
</template>

<style scoped>
.dept-manage {
  display: flex;
  gap: 16px;
  height: 100%;
}

.dept-tree-panel {
  width: 240px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.panel-title {
  font-weight: 500;
  font-size: 14px;
  color: #262626;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.disabled-node {
  color: #bfbfbf;
}

.dept-content-panel {
  flex: 1;
  min-width: 0;
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
  padding: 0 3px;
  user-select: none;
}

.dept-content-panel :deep(.ant-btn-link) {
  font-size: 13px;
  height: auto;
}

.dept-content-panel :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}

@media (max-width: 767px) {
  .dept-manage {
    flex-direction: column;
    gap: 10px;
    padding: 0;
    height: auto;
    min-height: 100%;
  }
  .dept-manage .dept-tree-panel {
    width: 100%;
    flex: none;
    max-height: 240px;
    overflow: auto;
    box-sizing: border-box;
  }
  .dept-manage .dept-content-panel {
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
