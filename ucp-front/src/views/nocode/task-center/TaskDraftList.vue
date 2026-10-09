<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { errorMessage } from '@/nocode/data-center'
import { taskTime } from '@/nocode/task-center'
import type { TaskDraftSummary } from '@/types/nocode/task-center'
const emit = defineEmits<{ close: []; edit: [id: string] }>()
const api = useNocodePlatform().taskCenter,
  { confirm } = useTaskConfirmation()
const error = ref(''),
  deleting = ref('')
const columns = [
  { title: '任务草稿', key: 'title' },
  { title: '最近保存', key: 'updatedAt', width: 180 },
  { title: '操作', key: 'actions', width: 190 }
]
const table = useOsTablePage<TaskDraftSummary, { search: string }>({
  defaultQuery: () => ({ search: '' }),
  fetchFn: async () => {
    error.value = ''
    const list = await api.drafts()
    return { list, total: list.length }
  },
  onError: cause => {
    error.value = errorMessage(cause)
  }
})
async function remove(row: TaskDraftSummary) {
  if (deleting.value) return
  deleting.value = row.id
  try {
    if (!(await confirm('删除这份任务草稿？', '只删除自己的未发布草稿，不影响正式任务。', '删除草稿'))) return
    await api.draftDelete(row.id, row.revision)
    message.success('草稿已删除')
    await table.fetchData()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    deleting.value = ''
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    title="我的草稿"
    display-mode="drawer"
    :width="850"
    :show-footer="false"
    :wrap-form="false"
    :allow-switch-display="false"
    @cancel="emit('close')"
  >
    <template #formItems>
      <p class="task-list__hint">草稿仅你可见，加入任务池后才成为正式任务。</p>
      <a-alert v-if="error" type="error" :message="error" />
      <OsTablePage
        :columns="columns"
        :data-source="table.tableData.value"
        :loading="table.loading.value"
        :pagination="false"
        :show-index="false"
      >
        <template #title>未发布任务草稿</template>
        <template #actions>
          <a-button :loading="table.loading.value" :disabled="!!deleting" @click="table.fetchData">刷新</a-button>
        </template>
        <template #empty>
          <a-empty
            :description="
              error ? '草稿暂时无法加载，请重试。' : '暂无任务草稿。在新建任务时选择“保存草稿”，可在这里继续编辑。'
            "
          />
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">{{ record.title || '未命名任务' }}</template>
          <template v-else-if="column.key === 'updatedAt'">{{ taskTime(record.updatedAt) }}</template>
          <div v-else-if="column.key === 'actions'" class="nocode-table-actions">
            <a-button type="link" :disabled="!!deleting" @click="emit('edit', record.id)">继续编辑</a-button>
            <a-button
              type="link"
              danger
              :disabled="!!deleting"
              :loading="deleting === record.id"
              @click="remove(record)"
            >
              删除
            </a-button>
          </div>
        </template>
      </OsTablePage>
    </template>
  </OsModalForm>
</template>
