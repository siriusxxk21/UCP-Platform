<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ReloadOutlined, UndoOutlined } from '@ant-design/icons-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { fieldTypes } from '@/nocode/object-draft'
import { fieldRestoreError, type InactiveFieldCandidate } from '@/nocode/field-restoration'
import type { ObjectField } from '@/types/nocode/object'

const props = defineProps<{
  open: boolean
  candidates: InactiveFieldCandidate[]
  fields: ObjectField[]
  readOnly?: boolean
  loading: boolean
  error: string
}>()
const emit = defineEmits<{ close: []; reload: []; restore: [InactiveFieldCandidate] }>()
const width = ref(880)
const resize = () => {
  width.value = Math.min(880, window.innerWidth)
}
onMounted(() => {
  resize()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => window.removeEventListener('resize', resize))
const search = ref(''),
  page = ref(1),
  pageSize = ref(10)
const filtered = computed(() => {
  const text = search.value.trim().toLowerCase()
  return props.candidates.filter(item => !text || `${item.field.name} ${item.field.code}`.toLowerCase().includes(text))
})
watch(
  () => props.open,
  () => {
    search.value = ''
    page.value = 1
  }
)
watch([search, () => filtered.value.length], () => {
  page.value = 1
})
const columns = [
  { title: '字段名称', key: 'name', width: 160, ellipsis: true },
  { title: '字段编码', key: 'code', width: 170, ellipsis: true },
  { title: '类型', key: 'type', width: 120 },
  { title: '状态', key: 'state', width: 110 },
  { title: '操作', key: 'actions', width: 110, fixed: 'right' as const }
]
const reason = (item: InactiveFieldCandidate) =>
  props.readOnly ? '请先进入可编辑草稿；停用明细需先启用所属明细' : fieldRestoreError(item, props.fields)
</script>

<template>
  <OsModalForm
    :open="open"
    title="已停用字段"
    display-mode="drawer"
    :allow-switch-display="false"
    :width="width"
    :show-footer="true"
    @cancel="emit('close')"
  >
    <template #formItems>
      <p class="inactive-fields-hint">
        恢复后保留原编码和历史数据，保存草稿并发布后生效。应用表单、列表中已移除的配置需重新添加。
      </p>
      <a-alert v-if="error" type="error" show-icon :message="error" class="inactive-fields-notice" />
      <a-alert
        v-if="readOnly"
        type="info"
        show-icon
        message="当前仅可查看。请先进入可编辑草稿；停用明细需先启用所属明细。"
        class="inactive-fields-notice"
      />
      <OsTablePage
        class="nocode-embedded-table inactive-fields-table"
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        :row-key="(item: InactiveFieldCandidate) => item.field.key"
        :pagination="{ current: page, pageSize, showSizeChanger: true, showTotal: (total: number) => `共 ${total} 条` }"
        :scroll="{ x: 740 }"
        resizable
        show-column-settings
        column-settings-key="nocode-inactive-fields"
        @change="
          (value: { current?: number; pageSize?: number }) => {
            page = value.current ?? 1
            pageSize = value.pageSize ?? 10
          }
        "
      >
        <template #actions>
          <a-input
            v-model:value="search"
            aria-label="搜索已停用字段"
            placeholder="字段名称或编码"
            allow-clear
            style="width: 220px"
          />
          <a-button :loading="loading" @click="emit('reload')">
            <ReloadOutlined />
            刷新
          </a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">{{ record.field.name }}</template>
          <template v-else-if="column.key === 'code'">{{ record.field.code }}</template>
          <template v-else-if="column.key === 'type'">
            {{ fieldTypes.find(t => t.value === record.field.type)?.label || record.field.type }}
          </template>
          <template v-else-if="column.key === 'state'">
            <a-tag>{{ record.persisted ? '已停用' : '待保存停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-tooltip :title="reason(record)">
              <span>
                <a-button type="link" :disabled="loading || !!reason(record)" @click="emit('restore', record)">
                  <UndoOutlined />
                  恢复
                </a-button>
              </span>
            </a-tooltip>
            <p v-if="!record.restorable" class="inactive-fields-reason">{{ record.blockedReason }}</p>
          </template>
        </template>
      </OsTablePage>
    </template>
    <template #footer><a-button @click="emit('close')">关闭</a-button></template>
  </OsModalForm>
</template>

<style scoped>
.inactive-fields-hint {
  margin: 0 0 16px;
  color: var(--color-text-secondary, #667085);
  line-height: 1.7;
}
.inactive-fields-notice {
  margin-bottom: 12px;
}
.inactive-fields-reason {
  margin: 0;
  color: var(--color-text-secondary, #667085);
  font-size: 12px;
}
@media (max-width: 600px) {
  .inactive-fields-table :deep(.ant-card-head-wrapper) {
    flex-wrap: wrap;
  }
  .inactive-fields-table :deep(.ant-card-extra) {
    max-width: 100%;
    margin-inline-start: 0;
  }
  .inactive-fields-table :deep(.ant-card-extra > .ant-space) {
    max-width: 100%;
    flex-wrap: wrap;
  }
}
</style>
