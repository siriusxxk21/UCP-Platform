<script setup lang="ts">
/**
 * 空间管理
 *
 * 治理用户从管理列表查看可维护的个人、团队和业务空间；
 * 普通用户仍从「我的空间」中筛选个人空间及其可管理的团队空间，避免因缺少治理权限请求 403。
 */
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
  TeamOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import MemberSelect from '@/components/MemberSelect.vue'
import DeptSelector from '@/components/DeptSelector.vue'
import type { SelectedDept } from '@/components/DeptSelector.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useOsModalForm } from '@/composables/useOsModalForm'
import {
  createBusinessSpace,
  createDriveSpace,
  deleteDriveSpace,
  getManageSpaceList,
  getMySpaceList,
  updateDriveSpace
} from '@/api/drive/space'
import { getSimpleUserList } from '@/api/system/user'
import { DRIVE_SPACE_STATUS_OPTIONS, DRIVE_SPACE_TAG_MAP } from '@/types/drive'
import type { DriveId, DriveSpace, DriveSpaceSave } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatDateTime, formatFileSize } from '@/utils/format'
import PermissionDrawer from './components/PermissionDrawer.vue'
import DriveStorageDrawer from './components/DriveStorageDrawer.vue'
import './drive-list.css'

defineOptions({ name: 'DriveSpace' })

const GIGABYTE = 1024 ** 3

interface SpaceForm {
  id?: DriveId
  type: 'TEAM' | 'BIZ'
  name: string
  ownerId?: string
  ownerDeptId?: string
  quotaGb: number
  status: number
}

const canCreate = computed(() => hasPermission('drive:space:create'))
const canUpdate = computed(() => hasPermission('drive:space:update'))
const canQuery = computed(() => hasPermission('drive:space:query'))
const canGovern = computed(() => canQuery.value && (canCreate.value || canUpdate.value))
const canDelete = computed(() => hasPermission('drive:space:delete'))
const canViewStorage = computed(() => hasPermission('drive:storage:query'))
const storageOpen = ref(false)

const columns: TableColumnType[] = [
  { title: '空间名称', dataIndex: 'name', key: 'name', width: 240, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'type', key: 'type', width: 100 },
  { title: '归属', key: 'owner', width: 200, ellipsis: true },
  { title: '容量', key: 'capacity', width: 220 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 230, fixed: 'right' }
]

const {
  loading,
  tableData,
  pagination,
  handleTableChange: changeTable,
  fetchData
} = useOsTablePage<DriveSpace, Record<string, never>>({
  fetchFn: () => (canGovern.value ? getManageSpaceList() : getMySpaceList()),
  defaultQuery: () => ({}),
  afterFetch: list =>
    canGovern.value ? list : list.filter(space => space.type === 'PERSONAL' || space.role === 'MANAGER')
})

const userOptions = ref<Array<{ value: string; name: string; username?: string }>>([])
const deptSelectorVisible = ref(false)
const ownerDeptLabel = ref('')

const permissionOpen = ref(false)
const permissionSpaceId = ref<DriveId | null>(null)
const permissionSpaceName = ref('')

const modalForm = useOsModalForm<SpaceForm>({
  createFn: async data => {
    if (data.type === 'BIZ') {
      const save = toSave(data)
      await createBusinessSpace({ name: save.name, quotaBytes: save.quotaBytes })
      return
    }
    await createDriveSpace(toSave(data))
  },
  updateFn: async data => {
    await updateDriveSpace(toSave(data))
  },
  defaultForm: () => ({
    id: undefined,
    type: 'TEAM',
    name: '',
    ownerId: undefined,
    ownerDeptId: undefined,
    quotaGb: 0,
    status: 0
  }),
  titles: { add: '新建空间', edit: '编辑空间' },
  afterOpenEdit: () => {
    const space = tableData.value.find(item => String(item.id) === String(modalForm.formData.id))
    ownerDeptLabel.value = space?.ownerDeptName || ''
  },
  afterSuccess: () => {
    void fetchData()
  }
})

const formRules = {
  type: [{ required: true, message: '请选择空间类型' }],
  name: [
    { required: true, whitespace: true, message: '请输入空间名称' },
    { validator: validateSpaceName, trigger: 'change' }
  ],
  quotaGb: [{ required: true, message: '请输入容量配额，0 表示不限制' }]
}

async function loadUsers() {
  try {
    const list = await getSimpleUserList()
    userOptions.value = list.map(user => ({
      value: String(user.id),
      name: user.nickname || user.username || String(user.id),
      username: user.username
    }))
  } catch {
    userOptions.value = []
  }
}
void loadUsers()

function toSave(data: SpaceForm): DriveSpaceSave {
  return {
    id: data.id,
    name: data.name.trim(),
    ownerId: data.type === 'TEAM' ? data.ownerId || undefined : undefined,
    ownerDeptId: data.type === 'TEAM' ? data.ownerDeptId || undefined : undefined,
    quotaBytes: Math.round((data.quotaGb || 0) * GIGABYTE),
    status: data.status
  }
}

function validateSpaceName(_rule: unknown, value: unknown) {
  const limit = modalForm.formData.type === 'BIZ' ? 64 : 100
  return String(value || '').trim().length <= limit
    ? Promise.resolve()
    : Promise.reject(new Error(`最多 ${limit} 个字符`))
}

function edit(record: DriveSpace) {
  ownerDeptLabel.value = record.ownerDeptName || ''
  modalForm.openEdit({
    id: record.id,
    type: record.type === 'BIZ' ? 'BIZ' : 'TEAM',
    name: record.name,
    ownerId: record.ownerId ? String(record.ownerId) : undefined,
    ownerDeptId: record.ownerDeptId ? String(record.ownerDeptId) : undefined,
    quotaGb: record.quotaBytes ? Number((record.quotaBytes / GIGABYTE).toFixed(2)) : 0,
    status: record.status
  })
}

function onDeptChange(items: SelectedDept[]) {
  const picked = items[0]
  modalForm.formData.ownerDeptId = picked ? String(picked.id) : undefined
  ownerDeptLabel.value = picked ? picked.name : ''
}

function clearOwnerDept() {
  modalForm.formData.ownerDeptId = undefined
  ownerDeptLabel.value = ''
}

async function remove(record: DriveSpace) {
  try {
    await deleteDriveSpace(record.id)
    message.success('空间已删除')
    await fetchData()
  } catch {
    /* 空间内仍有内容等由后端统一校验并提示 */
  }
}

function ownerText(record: DriveSpace) {
  if (record.type === 'BIZ') return '平台业务空间'
  return record.ownerDeptName ? `${record.ownerName || '-'} / ${record.ownerDeptName}` : record.ownerName || '-'
}

function usedPercent(record: DriveSpace) {
  if (!record.quotaBytes) return 0
  return Math.min(100, Math.round((record.usedBytes / record.quotaBytes) * 100))
}

function quotaText(record: DriveSpace) {
  return record.quotaBytes
    ? `${formatFileSize(record.usedBytes || 0)} / ${formatFileSize(record.quotaBytes)}`
    : `${formatFileSize(record.usedBytes || 0)} / 不限制`
}

function openPermission(record: DriveSpace) {
  if (record.type === 'BIZ') return
  permissionSpaceId.value = record.id
  permissionSpaceName.value = record.name
  permissionOpen.value = true
}
</script>

<template>
  <div class="drive-page">
    <OsTablePage
      class="drive-table"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      title="空间管理"
      :scroll="{ x: 'max-content' }"
      show-column-settings
      column-settings-key="drive-space"
      resizable
      :tag-map="DRIVE_SPACE_TAG_MAP"
      @change="changeTable"
    >
      <template #actions>
        <a-button v-if="canViewStorage" @click="storageOpen = true">
          <SettingOutlined />
          存储配置
        </a-button>
        <a-button v-if="canCreate" type="primary" @click="modalForm.openAdd">
          <PlusOutlined />
          新建空间
        </a-button>
        <a-button :loading="loading" @click="fetchData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'owner'">{{ ownerText(record) }}</template>
        <template v-else-if="column.key === 'capacity'">
          <div class="drive-quota">
            <a-progress
              v-if="record.quotaBytes"
              :percent="usedPercent(record)"
              size="small"
              :show-info="false"
              :status="usedPercent(record) >= 90 ? 'exception' : 'normal'"
            />
            <span class="drive-quota__text">{{ quotaText(record) }}</span>
          </div>
        </template>
        <template v-else-if="column.key === 'createTime'">{{ formatDateTime(record.createTime || 0) }}</template>
        <template v-else-if="column.key === 'action'">
          <div class="drive-actions">
            <a-button v-if="record.type !== 'BIZ'" type="link" @click="openPermission(record)">
              <TeamOutlined />
              成员与权限
            </a-button>
            <a-button
              v-if="canUpdate && (record.type === 'TEAM' || record.type === 'BIZ')"
              type="link"
              @click="edit(record)"
            >
              <EditOutlined />
              编辑
            </a-button>
            <a-popconfirm
              v-if="canDelete && record.type === 'TEAM'"
              title="删除后空间不可恢复；空间内仍有内容时无法删除。确定删除吗？"
              ok-text="确定"
              cancel-text="取消"
              @confirm="remove(record)"
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

    <OsModalForm v-bind="modalForm.modalProps.value" v-on="modalForm.modalEvents" :rules="formRules" width="620px">
      <template #formItems="{ formData }">
        <a-form-item
          v-if="modalForm.isAdd.value"
          label="空间类型"
          name="type"
          extra="业务空间仅用于业务文件归档，不配置成员权限；创建后不可转换类型。"
        >
          <a-radio-group v-model:value="formData.type" button-style="solid">
            <a-radio-button value="TEAM">团队空间</a-radio-button>
            <a-radio-button value="BIZ">业务空间</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-else label="空间类型">
          <a-tag :color="formData.type === 'BIZ' ? 'blue' : 'purple'">
            {{ formData.type === 'BIZ' ? '业务空间' : '团队空间' }}
          </a-tag>
        </a-form-item>
        <a-form-item label="空间名称" name="name">
          <a-input
            v-model:value="formData.name"
            placeholder="请输入空间名称"
            :maxlength="formData.type === 'BIZ' ? 64 : 100"
            show-count
          />
        </a-form-item>
        <a-form-item
          v-if="formData.type === 'TEAM'"
          label="归属用户"
          name="ownerId"
          extra="留空表示归创建人所有；归属用户默认拥有空间管理权限。"
        >
          <MemberSelect
            v-model:value="formData.ownerId"
            :options="userOptions"
            placeholder="选择归属用户"
            allow-clear
          />
        </a-form-item>
        <a-form-item
          v-if="formData.type === 'TEAM'"
          label="归属部门"
          name="ownerDeptId"
          extra="归属部门成员按部门授权获得空间权限。"
        >
          <a-space>
            <a-tag v-if="formData.ownerDeptId" closable @close="clearOwnerDept">
              {{ ownerDeptLabel || formData.ownerDeptId }}
            </a-tag>
            <a-button @click="deptSelectorVisible = true">
              {{ formData.ownerDeptId ? '重新选择' : '选择部门' }}
            </a-button>
          </a-space>
        </a-form-item>
        <a-form-item label="容量配额" name="quotaGb" extra="按 GB 填写，0 表示不限制；已用容量不含回收站内容。">
          <a-input-number
            v-model:value="formData.quotaGb"
            :min="0"
            :precision="2"
            :step="10"
            addon-after="GB"
            style="width: 200px"
          />
        </a-form-item>
        <a-form-item v-if="modalForm.isEdit.value" label="状态" name="status">
          <a-radio-group v-model:value="formData.status" :options="DRIVE_SPACE_STATUS_OPTIONS" />
        </a-form-item>
      </template>
    </OsModalForm>

    <DeptSelector
      v-if="deptSelectorVisible"
      v-model:visible="deptSelectorVisible"
      title="选择归属部门"
      @change="onDeptChange"
    />
    <PermissionDrawer
      :open="permissionOpen"
      :space-id="permissionSpaceId"
      :entry-id="0"
      :entry-name="permissionSpaceName"
      :can-edit="true"
      @close="permissionOpen = false"
      @changed="fetchData"
    />
    <DriveStorageDrawer :open="storageOpen" @close="storageOpen = false" />
  </div>
</template>
