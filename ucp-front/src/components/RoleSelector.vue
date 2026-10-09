<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import { getRoleList } from '@/api/system/role'
import type { Role } from '@/types/system/system'

export interface SelectedRole {
  id: string
  name: string
}

const props = withDefaults(
  defineProps<{
    visible?: boolean
    modelValue?: Array<string | number>
    multiple?: boolean
    title?: string
    maxCount?: number
    width?: number | string
  }>(),
  {
    visible: false,
    modelValue: () => [],
    multiple: false,
    title: '选择角色',
    maxCount: 0,
    width: 640
  }
)

const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
  (e: 'update:modelValue', value: Array<string | number>): void
  (e: 'change', items: SelectedRole[]): void
}>()

// ============================== 状态 ==============================

const roleList = ref<Role[]>([])
const loading = ref(false)
const searchName = ref('')
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0
})
/** 弹窗内选中的角色 ID 集合 */
const selectedIds = ref<Set<string>>(new Set())
/** 弹窗打开时快照，用于取消时回退 */
const snapshotIds = ref<Set<string>>(new Set())

// ============================== 数据获取 ==============================

async function fetchRoleList() {
  loading.value = true
  try {
    const res = await getRoleList({
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      name: searchName.value || undefined,
      status: 0 // 仅加载启用角色
    })
    roleList.value = Array.isArray(res.list) ? res.list : []
    pagination.total = Number(res.total || 0)
  } finally {
    loading.value = false
  }
}

// ============================== 事件处理 ==============================

function handleSearch() {
  pagination.current = 1
  fetchRoleList()
}

function handlePageChange(page: number, pageSize: number) {
  pagination.current = page
  pagination.pageSize = pageSize
  fetchRoleList()
}

function toggleSelect(role: Role) {
  const id = role.id
  if (!props.multiple) {
    // 单选：直接确认
    emit('update:modelValue', [id])
    emit('change', [{ id, name: role.name }])
    emit('update:visible', false)
    return
  }
  // 多选：切换选中
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    if (props.maxCount > 0 && selectedIds.value.size >= props.maxCount) return
    selectedIds.value.add(id)
  }
  // 触发响应式更新
  selectedIds.value = new Set(selectedIds.value)
}

function isSelected(id: string): boolean {
  return selectedIds.value.has(id)
}

function handleConfirm() {
  const items: SelectedRole[] = []
  for (const role of roleList.value) {
    if (selectedIds.value.has(role.id)) {
      items.push({ id: role.id, name: role.name })
    }
  }
  emit('update:modelValue', items.map(item => item.id))
  emit('change', items)
  emit('update:visible', false)
}

function handleCancel() {
  selectedIds.value = new Set(snapshotIds.value)
  emit('update:visible', false)
}

// ============================== 弹窗打开逻辑 ==============================

watch(
  () => props.visible,
  val => {
    if (val) {
      searchName.value = ''
      pagination.current = 1
      snapshotIds.value = new Set(props.modelValue.map(String))
      selectedIds.value = new Set(snapshotIds.value)
      fetchRoleList()
    }
  }
)

defineExpose({
  open() {
    emit('update:visible', true)
  }
})
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
    <div class="role-panel">
      <div class="panel-search">
        <a-input
          v-model:value="searchName"
          placeholder="请输入角色名称"
          allow-clear
          @press-enter="handleSearch"
        >
          <template #prefix>
            <SearchOutlined />
          </template>
        </a-input>
      </div>
      <div class="panel-table">
        <a-table
          :data-source="roleList"
          :columns="tableColumns"
          :pagination="{
            current: pagination.current,
            pageSize: pagination.pageSize,
            total: pagination.total,
            showSizeChanger: true,
            showTotal: (total: number) => `共 ${total} 条`
          }"
          :loading="loading"
          :row-key="(record: Role) => record.id"
          :custom-row="(record: Role) => ({
            onClick: () => toggleSelect(record)
          })"
          size="small"
          @change="(pag: any) => handlePageChange(pag.current, pag.pageSize)"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'selection'">
              <a-checkbox
                :checked="isSelected(record.id)"
                @click.stop
                @change="() => toggleSelect(record)"
              />
            </template>

          </template>
        </a-table>
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

<script lang="ts">
export default {
  computed: {
    tableColumns() {
      return [
        {
          title: '',
          key: 'selection',
          width: 48,
          align: 'center' as const
        },
        {
          title: '角色名称',
          dataIndex: 'name',
          key: 'name',
          ellipsis: true
        },
        {
          title: '角色编码',
          dataIndex: 'code',
          key: 'code',
          ellipsis: true
        }
      ]
    }
  }
}
</script>

<style scoped>
.role-panel {
  background: #f9fafb;
}

.panel-search {
  padding: 16px 16px 0;
}

.panel-table {
  padding: 16px;
}

:deep(.ant-modal-footer) {
  padding: 0;
  margin-top: 0;
  border-top: none;
}

:deep(.ant-modal-content) {
  padding: 0;
}

.selector-footer {
  padding: 12px 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

:deep(.ant-table-row) {
  cursor: pointer;
}
</style>
