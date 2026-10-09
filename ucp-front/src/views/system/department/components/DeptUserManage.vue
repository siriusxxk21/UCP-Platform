<script setup lang="ts">
import {reactive, ref, watch} from 'vue'
import {message, Modal} from 'ant-design-vue'
import type {Department, UserDept} from '@/api/system/department'
import {
  batchAddUsersToDept,
  getAvailableUsersPage,
  getDeptUsersPage,
  removeUserFromDept,
  setAsMainDept,
} from '@/api/system/department'

const props = defineProps<{
  visible: boolean
  dept: Department | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
}>()

const deptName = ref('')

// 已加入用户
const deptUserLoading = ref(false)
const deptUsers = ref<UserDept[]>([])
const deptUserSearchKey = ref('')
const deptUserTotal = ref(0)
const deptUserPagination = reactive({ current: 1, pageSize: 10 })

// 可添加用户
const availableUserLoading = ref(false)
const availableUsers = ref<UserDept[]>([])
const availableUserSearchKey = ref('')
const availableUserTotal = ref(0)
const availableUserPagination = reactive({ current: 1, pageSize: 10 })

// 添加用户
const addUserLoading = ref(false)
const selectedUserIds = ref<string[]>([])
const addUserPost = ref('')
const addUserIsMain = ref(false)

const deptUserColumns = [
  {
    title: '序号',
    key: 'index',
    width: 60,
    align: 'center',
    customRender: ({ index }: { index: number }) => {
      return (deptUserPagination.current - 1) * deptUserPagination.pageSize + index + 1
    },
  },
  { title: '用户名', dataIndex: 'username', key: 'username', width: 100 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 100 },
  { title: '岗位', dataIndex: 'post', key: 'post', width: 80 },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 120 },
  { title: '状态', dataIndex: 'userStatus', key: 'userStatus', width: 70 },
  { title: '主部门', dataIndex: 'isMain', key: 'isMain', width: 70 },
  { title: '操作', key: 'action', width: 130 },
]

const availableUserColumns = [
  {
    title: '序号',
    key: 'index',
    width: 60,
    align: 'center',
    customRender: ({ index }: { index: number }) => {
      return (availableUserPagination.current - 1) * availableUserPagination.pageSize + index + 1
    },
  },
  { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 120 },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 120 },
  { title: '状态', dataIndex: 'userStatus', key: 'userStatus', width: 80 },
]

// 监听弹窗打开
watch(() => props.visible, (val) => {
  if (val && props.dept) {
    deptName.value = props.dept.deptName
    resetState()
    loadDeptUsers()
    loadAvailableUsers()
  }
})

function resetState() {
  deptUserSearchKey.value = ''
  deptUserPagination.current = 1
  deptUserPagination.pageSize = 10

  availableUserSearchKey.value = ''
  availableUserPagination.current = 1
  availableUserPagination.pageSize = 10

  selectedUserIds.value = []
  addUserPost.value = ''
  addUserIsMain.value = false
}

// 加载已加入用户
async function loadDeptUsers() {
  if (!props.dept)
    return
  deptUserLoading.value = true
  try {
    const res = await getDeptUsersPage(props.dept.id, {
      pageNum: deptUserPagination.current,
      pageSize: deptUserPagination.pageSize,
      username: deptUserSearchKey.value || undefined,
    })
    deptUsers.value = res.records || []
    deptUserTotal.value = res.total || 0
  }
  catch (e) {
    console.error('加载部门用户失败', e)
  }
  finally {
    deptUserLoading.value = false
  }
}

// 加载可添加用户
async function loadAvailableUsers() {
  if (!props.dept)
    return
  availableUserLoading.value = true
  try {
    const res = await getAvailableUsersPage(props.dept.id, {
      pageNum: availableUserPagination.current,
      pageSize: availableUserPagination.pageSize,
      username: availableUserSearchKey.value || undefined,
    })
    availableUsers.value = res.records || []
    availableUserTotal.value = res.total || 0
  }
  catch (e) {
    console.error('加载可添加用户失败', e)
  }
  finally {
    availableUserLoading.value = false
  }
}

// 分页变化
function handleDeptUserPageChange(pagination: { current: number, pageSize: number }) {
  deptUserPagination.current = pagination.current
  deptUserPagination.pageSize = pagination.pageSize
  loadDeptUsers()
}

function handleAvailableUserPageChange(pagination: { current: number, pageSize: number }) {
  availableUserPagination.current = pagination.current
  availableUserPagination.pageSize = pagination.pageSize
  loadAvailableUsers()
}

// 添加用户
async function handleAddUserConfirm() {
  if (selectedUserIds.value.length === 0) {
    message.warning('请选择要添加的用户')
    return
  }
  if (!props.dept)
    return

  addUserLoading.value = true
  try {
    await batchAddUsersToDept(props.dept.id, {
      userIds: selectedUserIds.value,
      post: addUserPost.value || undefined,
      isMain: addUserIsMain.value,
    })
    message.success('添加成功')
    selectedUserIds.value = []
    addUserPost.value = ''
    addUserIsMain.value = false
    // 刷新两个列表
    await Promise.all([loadDeptUsers(), loadAvailableUsers()])
  }
  catch (e) {
    console.error('添加用户失败', e)
  }
  finally {
    addUserLoading.value = false
  }
}

// 移除用户
function handleRemoveUser(record: UserDept) {
  Modal.confirm({
    title: '确认移除',
    content: `确定要将用户 "${record.nickname || record.username}" 从该部门移除吗？`,
    okType: 'danger',
    onOk: async () => {
      try {
        await removeUserFromDept(record.id!)
        message.success('移除成功')
        await Promise.all([loadDeptUsers(), loadAvailableUsers()])
      }
      catch (e) {
        console.error('移除用户失败', e)
      }
    },
  })
}

// 设为主部门
async function handleSetMainDept(record: UserDept) {
  if (!props.dept)
    return
  try {
    await setAsMainDept(record.id!, props.dept.id)
    message.success('设置成功')
    await loadDeptUsers()
  }
  catch (e) {
    console.error('设置主部门失败', e)
  }
}

function handleCancel() {
  emit('update:visible', false)
}
</script>

<template>
  <a-modal
    :open="visible"
    :title="`部门用户管理 - ${deptName}`"
    width="1200px"
    :footer="null"
    @cancel="handleCancel"
  >
    <div class="user-modal-content">
      <!-- 上栏：已加入用户 -->
      <div class="user-section">
        <div class="section-header">
          <span class="section-title">已加入用户 (共{{ deptUserTotal }}人)</span>
          <a-input-search
            v-model:value="deptUserSearchKey"
            placeholder="搜索用户名/昵称/拼音"
            style="width: 220px"
            size="middle"
            allow-clear
            @search="loadDeptUsers"
          />
        </div>
        <a-table
          :columns="deptUserColumns"
          :data-source="deptUsers"
          :loading="deptUserLoading"
          row-key="id"
          size="small"
          :scroll="{ y: 200 }"
          :pagination="{
            current: deptUserPagination.current,
            pageSize: deptUserPagination.pageSize,
            total: deptUserTotal,
            size: 'small',
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total: number) => `共 ${total} 条`,
          }"
          @change="handleDeptUserPageChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'userStatus'">
              <a-tag :color="record.userStatus === 0 ? 'green' : 'red'">
                {{ record.userStatus === 0 ? '启用' : '禁用' }}
              </a-tag>
            </template>
            <template v-if="column.key === 'isMain'">
              <a-tag v-if="record.isMain === 1" color="blue">
                主部门
              </a-tag>
              <span v-else>-</span>
            </template>
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button v-if="record.isMain !== 1" type="link" @click="handleSetMainDept(record)">
                  设为主部门
                </a-button>
                <a-button v-if="record.isMain !== 1" type="link" danger @click="handleRemoveUser(record)">
                  移除
                </a-button>
                <a-tooltip v-else title="请先将其他关联部门设为主部门">
                  <a-button type="link" danger disabled>
                    移除
                  </a-button>
                </a-tooltip>
              </a-space>
            </template>
          </template>
        </a-table>
      </div>

      <!-- 分隔线 -->
      <a-divider style="margin: 16px 0" />

      <!-- 下栏：添加用户 -->
      <div class="user-section">
        <div class="section-header">
          <span class="section-title">添加用户</span>
          <a-input-search
            v-model:value="availableUserSearchKey"
            placeholder="搜索用户名/昵称/拼音"
            style="width: 220px"
            size="middle"
            allow-clear
            @search="loadAvailableUsers"
          />
        </div>
        <a-table
          :columns="availableUserColumns"
          :data-source="availableUsers"
          :loading="availableUserLoading"
          :row-selection="{
            selectedRowKeys: selectedUserIds,
            onChange: (keys: string[]) => { selectedUserIds = keys },
          }"
          row-key="userId"
          size="small"
          :scroll="{ y: 200 }"
          :pagination="{
            current: availableUserPagination.current,
            pageSize: availableUserPagination.pageSize,
            total: availableUserTotal,
            size: 'small',
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total: number) => `共 ${total} 条`,
          }"
          @change="handleAvailableUserPageChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'userStatus'">
              <a-tag :color="record.userStatus === 0 ? 'green' : 'red'">
                {{ record.userStatus === 0 ? '启用' : '禁用' }}
              </a-tag>
            </template>
          </template>
        </a-table>
        <div class="add-user-footer">
          <div class="add-user-options">
            <span class="option-item">
              岗位：
              <a-input v-model:value="addUserPost" placeholder="请输入岗位" style="width: 120px" size="small" />
            </span>
            <span class="option-item">
              设为主部门：
              <a-switch v-model:checked="addUserIsMain" size="small" />
            </span>
          </div>
          <a-button
            type="primary"
            :loading="addUserLoading"
            :disabled="selectedUserIds.length === 0"
            @click="handleAddUserConfirm"
          >
            添加选中用户 ({{ selectedUserIds.length }})
          </a-button>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<style scoped>
.user-modal-content {
  min-height: 520px;
}

.user-section {
  margin-bottom: 8px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-title {
  font-weight: 500;
  font-size: 14px;
  color: #262626;
}

.add-user-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.add-user-options {
  display: flex;
  gap: 16px;
}

.option-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #595959;
}
</style>
