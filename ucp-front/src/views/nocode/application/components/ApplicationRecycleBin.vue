<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, ReloadOutlined, SearchOutlined, UndoOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { managementCategoryLabel } from '@/nocode/management-category'
import { formatDateTime } from '@/utils/format'
import type { RecycledApplicationRow } from '@/types/nocode/application'

const emit = defineEmits<{ back: []; edit: [id: string] }>()
const api = useNocodePlatform().applications
const query = reactive({ pageNo: 1, pageSize: 10, search: '' })
const rows = ref<RecycledApplicationRow[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref('')
const selected = ref<RecycledApplicationRow>(),
  restoring = ref(false),
  restoreError = ref(''),
  reason = ref('')
const restored = ref<{ id: string; name: string }>()
const columns = [
  { title: '应用名称', key: 'name', dataIndex: 'name', width: 200, ellipsis: true },
  { title: '原分类', key: 'category', width: 120, ellipsis: true },
  { title: '应用编码', key: 'code', dataIndex: 'code', width: 160, ellipsis: true },
  { title: '删除人', key: 'deletedBy', width: 110, ellipsis: true },
  { title: '删除时间', key: 'deletedAt', width: 180 },
  { title: '操作', key: 'action', width: 100, fixed: 'right' as const }
]
const pagination = computed(() => ({
  current: query.pageNo,
  pageSize: query.pageSize,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (count: number) => `共 ${count} 条`
}))
let request = 0
async function load() {
  const current = ++request
  loading.value = true
  error.value = ''
  try {
    const result = await api.recyclePage({ ...query })
    if (current !== request) return
    const last = Math.max(1, Math.ceil(result.total / query.pageSize))
    if (query.pageNo > last) {
      query.pageNo = last
      return await load()
    }
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    if (current === request) {
      rows.value = []
      total.value = 0
      error.value = errorMessage(e)
    }
  } finally {
    if (current === request) loading.value = false
  }
}
function search() {
  query.pageNo = 1
  void load()
}
function reset() {
  query.search = ''
  search()
}
function changePage(page: { current?: number; pageSize?: number }) {
  query.pageNo = page.pageSize !== query.pageSize ? 1 : (page.current ?? 1)
  query.pageSize = page.pageSize ?? 10
  void load()
}
function openRestore(row: RecycledApplicationRow) {
  selected.value = row
  reason.value = ''
  restoreError.value = ''
}
async function restore() {
  if (!selected.value || restoring.value || !reason.value.trim()) return
  restoring.value = true
  restoreError.value = ''
  try {
    const result = await api.restoreRecycled({
      id: selected.value.id,
      expectedRevision: selected.value.revision,
      reason: reason.value.trim()
    })
    restored.value = { id: result.application.id, name: result.application.name }
    selected.value = undefined
    message.success('应用已恢复，当前保持停用')
    await load()
  } catch (e) {
    restoreError.value = errorMessage(e)
  } finally {
    restoring.value = false
  }
}
onMounted(load)
onScopeDispose(() => request++)
</script>

<template>
  <section class="nocode-list-page">
    <a-alert v-if="error" class="recycle-notice" type="error" show-icon :message="error" />
    <a-alert
      v-if="restored"
      class="recycle-notice"
      type="success"
      show-icon
      :message="`“${restored.name}”已恢复，保持停用`"
    >
      <template #description>
        人工编辑并保存后，再发布启用。
        <a-button type="link" @click="emit('edit', restored.id)">前往编辑</a-button>
      </template>
    </a-alert>
    <OsTablePage
      title="应用回收站"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 'max-content', y: '100%' }"
      show-column-settings
      column-settings-key="nocode-application-recycle"
      resizable
      row-key="id"
      @change="changePage"
      @search="search"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="query" @finish="triggerSearch">
          <a-form-item label="应用">
            <a-input
              v-model:value="query.search"
              aria-label="搜索回收站应用"
              placeholder="应用名称或编码"
              class="nocode-filter-input"
              allow-clear
              @press-enter.prevent="triggerSearch"
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="reset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template #actions>
        <a-button :disabled="restoring" @click="emit('back')">
          <ArrowLeftOutlined />
          返回应用列表
        </a-button>
        <a-button :disabled="restoring" @click="load">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'category'">
          <span :title="managementCategoryLabel(record.category)">{{ managementCategoryLabel(record.category) }}</span>
        </template>
        <template v-else-if="column.key === 'deletedBy'">
          <span :title="record.deletedByName || record.deletedBy">{{ record.deletedByName || record.deletedBy }}</span>
        </template>
        <template v-else-if="column.key === 'deletedAt'">{{ formatDateTime(record.deletedAt) }}</template>
        <div v-else-if="column.key === 'action'" class="nocode-table-actions">
          <a-button type="link" @click="openRestore(record)">
            <UndoOutlined />
            恢复
          </a-button>
        </div>
      </template>
    </OsTablePage>
    <OsModalForm
      :open="!!selected"
      title="恢复应用"
      :width="600"
      :loading="restoring"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      @cancel="!restoring && (selected = undefined)"
    >
      <template #formItems>
        <a-alert
          class="recycle-notice"
          type="info"
          show-icon
          :message="`恢复“${selected?.name || ''}”`"
          description="恢复后保持停用，应用不会自动开放。请人工编辑并保存，再发布启用。"
        />
        <a-alert v-if="restoreError" class="recycle-notice" type="error" show-icon :message="restoreError" />
        <a-form-item label="恢复说明" required>
          <a-textarea
            v-model:value="reason"
            aria-label="恢复说明"
            :maxlength="1000"
            :rows="3"
            :disabled="restoring"
            placeholder="说明恢复此应用的原因"
          />
        </a-form-item>
      </template>
      <template #footer>
        <a-space>
          <a-button :disabled="restoring" @click="selected = undefined">取消</a-button>
          <a-button type="primary" :loading="restoring" :disabled="!reason.trim()" @click="restore">
            恢复并保持停用
          </a-button>
        </a-space>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.recycle-notice {
  margin-bottom: 12px;
}
</style>
