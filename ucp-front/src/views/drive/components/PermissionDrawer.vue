<script setup lang="ts">
/**
 * 网盘授权抽屉
 *
 * 分两块呈现：直接授权回答「这一层配置了什么」，有效权限来源回答「我现在的角色从哪来」，
 * 继承场景下两者会不同，分开才能解释清楚。仅该节点的管理者可编辑，其他人只读查看。
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import DeptSelector from '@/components/DeptSelector.vue'
import type { SelectedDept } from '@/components/DeptSelector.vue'
import UserSelector from '@/components/UserSelector/index.vue'
import { isSelectableUser } from '@/components/UserSelector/userSelection'
import type { User } from '@/api/system/user'
import {
  deleteDrivePermission,
  getDrivePermissionList,
  getDrivePermissionSourceList,
  saveDrivePermission
} from '@/api/drive/permission'
import { DRIVE_ROLE_LABELS, DRIVE_ROLE_OPTIONS, DRIVE_SUBJECT_TYPE_LABELS } from '@/types/drive'
import type {
  DriveId,
  DrivePermission,
  DrivePermissionRole,
  DrivePermissionSource,
  DriveSubjectType
} from '@/types/drive'
import { formatDateTime } from '@/utils/format'
import { partitionPermissionSaveResults } from '../permission-batch'

const props = defineProps<{
  open: boolean
  spaceId: DriveId | null
  /** 被授权的节点编号，0 表示空间根 */
  entryId: DriveId
  entryName?: string
  /** 当前用户是否为该节点的管理者 */
  canEdit: boolean
}>()

const emit = defineEmits<{ close: []; changed: [] }>()

const tab = ref<'direct' | 'source'>('direct')
const loading = ref(false)
const saving = ref(false)
const removing = ref<DriveId | null>(null)
let loadVersion = 0
const permissions = ref<DrivePermission[]>([])
const sources = ref<DrivePermissionSource[]>([])

// 新增授权表单
const formVisible = ref(false)
const subjectType = ref<DriveSubjectType>('USER')
const pickedUsers = ref<User[]>([])
const pickedDepts = ref<SelectedDept[]>([])
const pickedDeptIds = ref<Array<string | number>>([])
const role = ref<DrivePermissionRole>('VIEWER')
const includeChildren = ref(true)
const userSelectorVisible = ref(false)
const deptSelectorVisible = ref(false)

const targetLabel = computed(() => props.entryName || (String(props.entryId) === '0' ? '空间根目录' : '当前目录'))
const pickedCount = computed(() => (subjectType.value === 'USER' ? pickedUsers.value.length : pickedDepts.value.length))
const invalidPickedUsers = computed(() => pickedUsers.value.filter(user => !isSelectableUser(user, true)))

const permissionColumns = [
  { title: '主体类型', dataIndex: 'subjectType', key: 'subjectType', width: 90 },
  { title: '主体名称', dataIndex: 'subjectName', key: 'subjectName', ellipsis: true },
  { title: '授权角色', dataIndex: 'role', key: 'role', width: 90 },
  { title: '含下级部门', key: 'includeChildren', width: 100 },
  { title: '授权时间', dataIndex: 'createTime', key: 'createTime', width: 165 },
  { title: '操作', key: 'action', width: 80 }
]

const sourceColumns = [
  { title: '授权主体', key: 'subject', ellipsis: true },
  { title: '有效角色', dataIndex: 'role', key: 'role', width: 90 },
  { title: '授权来源', key: 'source', ellipsis: true },
  { title: '含下级', key: 'includeChildren', width: 70 }
]

watch(
  () => [props.open, props.spaceId, props.entryId] as const,
  ([open]) => {
    loadVersion++
    permissions.value = []
    sources.value = []
    loading.value = false
    if (!open) return
    tab.value = 'direct'
    resetForm()
    void loadAll()
  }
)

async function loadAll() {
  if (props.spaceId === null) return
  const request = ++loadVersion
  loading.value = true
  try {
    const [direct, effective] = await Promise.all([
      getDrivePermissionList({ spaceId: props.spaceId, entryId: props.entryId }),
      getDrivePermissionSourceList({ spaceId: props.spaceId, entryId: props.entryId })
    ])
    if (request !== loadVersion) return
    permissions.value = direct || []
    sources.value = effective || []
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    if (request === loadVersion) loading.value = false
  }
}

function resetForm() {
  formVisible.value = false
  subjectType.value = 'USER'
  pickedUsers.value = []
  pickedDepts.value = []
  pickedDeptIds.value = []
  role.value = 'VIEWER'
  includeChildren.value = true
}

function onSubjectTypeChange() {
  pickedUsers.value = []
  pickedDepts.value = []
  pickedDeptIds.value = []
}

function confirmUsers(users: User[]) {
  pickedUsers.value = users
  userSelectorVisible.value = false
}

function onDeptChange(items: SelectedDept[]) {
  pickedDepts.value = items
  pickedDeptIds.value = items.map(item => item.id)
}

async function save() {
  if (props.spaceId === null || saving.value) return
  const spaceId = props.spaceId
  const entryId = props.entryId
  if (!pickedCount.value) {
    message.warning(subjectType.value === 'USER' ? '请选择授权人员' : '请选择授权部门')
    return
  }
  if (subjectType.value === 'USER' && invalidPickedUsers.value.length) {
    message.error('已选人员中包含已停用账号，请移除后重新保存授权')
    return
  }
  saving.value = true
  const isUser = subjectType.value === 'USER'
  const selectedSubjects = isUser
    ? pickedUsers.value.map(user => ({ id: user.id, label: user.nickname || user.username || String(user.id) }))
    : pickedDepts.value.map(dept => ({ id: dept.id, label: dept.name || String(dept.id) }))
  const subjects = selectedSubjects.map(subject => subject.id)
  try {
    const results = await Promise.allSettled(
      subjects.map(subjectId =>
        saveDrivePermission({
          spaceId,
          entryId,
          subjectType: subjectType.value,
          subjectId,
          role: role.value,
          includeChildren: isUser ? undefined : includeChildren.value
        })
      )
    )
    if (!props.open || props.spaceId !== spaceId || props.entryId !== entryId) return
    const { succeededIds, failures } = partitionPermissionSaveResults(selectedSubjects, results)
    if (failures.length) {
      if (isUser) pickedUsers.value = pickedUsers.value.filter(user => !succeededIds.has(String(user.id)))
      else {
        pickedDepts.value = pickedDepts.value.filter(dept => !succeededIds.has(String(dept.id)))
        pickedDeptIds.value = pickedDepts.value.map(dept => dept.id)
      }
      await loadAll()
      if (succeededIds.size) emit('changed')
      const failureDetails = failures.map(item => {
        const reason = item.reason instanceof Error ? item.reason.message : '操作失败，请重试'
        return `${item.subject.label}：${reason}`
      })
      const detail = failureDetails.join('；')
      message.error(
        succeededIds.size
          ? `已保存 ${succeededIds.size} 项，${failures.length} 项失败。${detail}。失败项已保留，可修正后重试。`
          : `授权保存失败：${detail}`
      )
      return
    }
    message.success('授权已保存')
    resetForm()
    await loadAll()
    emit('changed')
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    saving.value = false
  }
}

async function remove(record: DrivePermission) {
  if (removing.value !== null) return
  removing.value = record.id
  try {
    await deleteDrivePermission(record.id)
    message.success('授权已移除')
    await loadAll()
    emit('changed')
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    removing.value = null
  }
}

function sourceLabel(record: DrivePermissionSource) {
  if (record.ownerGrant) return '空间归属主体'
  return record.sourceEntryName || '空间级授权'
}
</script>

<template>
  <a-drawer
    :open="open"
    :title="`成员与权限 · ${targetLabel}`"
    width="min(720px, 100vw)"
    class="drive-permission-drawer"
    @close="emit('close')"
  >
    <a-alert
      v-if="!canEdit"
      class="drive-permission-drawer__notice"
      type="info"
      show-icon
      message="当前为只读查看。只有该目录的管理者可以调整授权。"
    />
    <a-tabs v-model:active-key="tab">
      <a-tab-pane key="direct" tab="直接授权">
        <div class="drive-permission-drawer__toolbar">
          <span class="drive-permission-drawer__hint">
            仅列出配置在本层的授权；上级继承来的授权不在此处，可在「有效权限来源」查看。
          </span>
          <a-space>
            <a-button v-if="canEdit" type="primary" @click="formVisible = !formVisible">
              <PlusOutlined />
              添加授权
            </a-button>
            <a-button :loading="loading" @click="loadAll">
              <ReloadOutlined />
              刷新
            </a-button>
          </a-space>
        </div>
        <div v-if="formVisible && canEdit" class="drive-permission-drawer__form">
          <a-form layout="vertical">
            <a-form-item label="授权主体">
              <a-radio-group v-model:value="subjectType" button-style="solid" @change="onSubjectTypeChange">
                <a-radio-button value="USER">用户</a-radio-button>
                <a-radio-button value="DEPT">部门</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item :label="subjectType === 'USER' ? '选择人员' : '选择部门'">
              <a-space wrap>
                <template v-if="subjectType === 'USER'">
                  <a-tag
                    v-for="user in pickedUsers"
                    :key="user.id"
                    closable
                    @close="confirmUsers(pickedUsers.filter(item => item.id !== user.id))"
                  >
                    {{ user.nickname }}
                  </a-tag>
                  <a-button @click="userSelectorVisible = true">
                    {{ pickedUsers.length ? '重新选择' : '选择人员' }}
                  </a-button>
                </template>
                <template v-else>
                  <a-tag
                    v-for="dept in pickedDepts"
                    :key="dept.id"
                    closable
                    @close="onDeptChange(pickedDepts.filter(item => item.id !== dept.id))"
                  >
                    {{ dept.name }}
                  </a-tag>
                  <a-button @click="deptSelectorVisible = true">
                    {{ pickedDepts.length ? '重新选择' : '选择部门' }}
                  </a-button>
                </template>
              </a-space>
              <a-alert
                v-if="subjectType === 'USER' && invalidPickedUsers.length"
                class="drive-permission-drawer__invalid"
                type="error"
                show-icon
                message="已选人员包含停用账号，请移除后再保存授权。"
              />
            </a-form-item>
            <a-form-item label="授权角色">
              <a-select v-model:value="role" :options="DRIVE_ROLE_OPTIONS" style="width: 200px" />
            </a-form-item>
            <a-form-item v-if="subjectType === 'DEPT'" label="部门范围">
              <a-checkbox v-model:checked="includeChildren">含下级部门</a-checkbox>
            </a-form-item>
            <a-space>
              <a-button type="primary" :loading="saving" @click="save">保存授权</a-button>
              <a-button @click="resetForm">取消</a-button>
            </a-space>
          </a-form>
        </div>
        <a-table
          :columns="permissionColumns"
          :data-source="permissions"
          :loading="loading"
          :pagination="false"
          row-key="id"
          size="small"
          :scroll="{ x: 'max-content' }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'subjectType'">
              <a-tag :color="record.subjectType === 'USER' ? 'blue' : 'purple'">
                {{ DRIVE_SUBJECT_TYPE_LABELS[record.subjectType as DriveSubjectType] }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'subjectName'">
              {{ record.subjectName || record.subjectId }}
            </template>
            <template v-else-if="column.key === 'role'">
              {{ DRIVE_ROLE_LABELS[record.role as DrivePermissionRole] }}
            </template>
            <template v-else-if="column.key === 'includeChildren'">
              {{ record.subjectType === 'DEPT' ? (record.includeChildren ? '是' : '否') : '-' }}
            </template>
            <template v-else-if="column.key === 'createTime'">{{ formatDateTime(record.createTime || 0) }}</template>
            <template v-else-if="column.key === 'action'">
              <a-popconfirm
                v-if="canEdit"
                title="确定移除该授权吗？移除后对方将按上级授权重新判定权限。"
                ok-text="确定"
                cancel-text="取消"
                @confirm="remove(record)"
              >
                <a-button
                  type="link"
                  danger
                  :loading="removing === record.id"
                  :disabled="removing !== null && removing !== record.id"
                >
                  <DeleteOutlined />
                  移除
                </a-button>
              </a-popconfirm>
              <span v-else>-</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane key="source" tab="有效权限来源">
        <p class="drive-permission-drawer__hint">
          说明当前角色由哪些授权判定得出，包含空间归属主体与上级继承来的授权。
        </p>
        <a-table
          :columns="sourceColumns"
          :data-source="sources"
          :loading="loading"
          :pagination="false"
          row-key="id"
          size="small"
          :scroll="{ x: 'max-content' }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'subject'">
              <a-tag :color="record.subjectType === 'USER' ? 'blue' : 'purple'">
                {{ DRIVE_SUBJECT_TYPE_LABELS[record.subjectType as DriveSubjectType] }}
              </a-tag>
              {{ record.subjectName || record.subjectId }}
            </template>
            <template v-else-if="column.key === 'role'">
              {{ DRIVE_ROLE_LABELS[record.role as DrivePermissionRole] }}
            </template>
            <template v-else-if="column.key === 'source'">
              {{ sourceLabel(record) }}
              <a-tag v-if="record.ownerGrant" color="green">归属主体</a-tag>
            </template>
            <template v-else-if="column.key === 'includeChildren'">
              {{ record.subjectType === 'DEPT' ? (record.includeChildren ? '是' : '否') : '-' }}
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>
    <UserSelector
      v-if="userSelectorVisible"
      v-model:visible="userSelectorVisible"
      multiple
      :selected-users="pickedUsers"
      enabled-only
      title="选择授权人员"
      @confirm="confirmUsers"
    />
    <DeptSelector
      v-if="deptSelectorVisible"
      v-model:visible="deptSelectorVisible"
      v-model="pickedDeptIds"
      multiple
      title="选择授权部门"
      @change="onDeptChange"
    />
  </a-drawer>
</template>

<style scoped>
.drive-permission-drawer__notice {
  margin-bottom: var(--spacing-md);
}

.drive-permission-drawer__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.drive-permission-drawer__hint {
  color: var(--text-secondary);
  font-size: 12px;
}

.drive-permission-drawer__form {
  padding: var(--spacing-md) var(--spacing-lg) var(--spacing-xs);
  margin-bottom: var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-page);
}

.drive-permission-drawer__invalid {
  margin-top: var(--spacing-sm);
}
</style>
