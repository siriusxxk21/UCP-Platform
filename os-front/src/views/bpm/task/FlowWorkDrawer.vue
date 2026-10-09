<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { EyeOutlined, FormOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { createFlowWorkIndexApi, type FlowWorkItem } from '@/api/nocode/flow-work-index'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import { WorkDraftState, type WorkCursor } from '@/types/nocode/work'
import { formatDateTime } from '@/utils/format'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import RecordSurface from '@/views/nocode/application/components/RecordSurface.vue'

const props = defineProps<{ open: boolean; initialState: WorkDraftState }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const router = useRouter()
const api = createFlowWorkIndexApi(request)
const state = ref<WorkDraftState>(props.initialState)
const items = ref<FlowWorkItem[]>([])
const before = ref<WorkCursor | null>(null)
const loading = ref(false)
const navigating = ref('')
const error = ref('')
let generation = 0
let retryAppend = false
const isDraft = computed(() => state.value === WorkDraftState.DRAFT)
const columns = [
  { title: '办理表单', dataIndex: 'formName', key: 'formName', width: 180, ellipsis: true },
  { title: '业务对象', dataIndex: 'objectName', key: 'objectName', width: 130, ellipsis: true },
  { title: '应用版本', dataIndex: 'applicationVersion', key: 'applicationVersion', width: 100 },
  { title: '流程编号', dataIndex: 'processInstanceId', key: 'processInstanceId', width: 230, ellipsis: true },
  { title: '任务编号', dataIndex: 'taskId', key: 'taskId', width: 230, ellipsis: true },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180 },
  { title: '状态', key: 'state', width: 110 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' as const }
]

async function load(append = false) {
  if (!props.open || (append && (loading.value || !before.value))) return
  const stamp = ++generation
  retryAppend = append
  loading.value = true
  error.value = ''
  if (!append) {
    items.value = []
    before.value = null
  }
  try {
    const page = await api.page({ state: state.value, before: before.value, limit: 10 })
    if (stamp !== generation) return
    const merged = append ? [...items.value, ...page.items] : page.items
    items.value = [...new Map(merged.map(item => [item.draftId, item])).values()]
    before.value = page.before
  } catch (e) {
    if (stamp === generation) error.value = errorMessage(e)
  } finally {
    if (stamp === generation) loading.value = false
  }
}

function changeState(value: string | number) {
  if (value !== WorkDraftState.DRAFT && value !== WorkDraftState.SUBMITTED) return
  state.value = value
  void load()
}

async function openTask(item: FlowWorkItem) {
  if (navigating.value || (item.state === WorkDraftState.DRAFT && !item.writable)) return
  navigating.value = item.draftId
  error.value = ''
  try {
    // 所有入口进入同一办理页；重新打开时仍由服务端验证资格，列表不是授权凭证。
    const failure = await router.push({ name: 'NocodeFlowTask', query: { taskId: item.taskId } })
    if (!failure) emit('update:open', false)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    navigating.value = ''
  }
}

watch(
  () => [props.open, props.initialState],
  () => {
    generation++
    items.value = []
    before.value = null
    error.value = ''
    loading.value = false
    if (props.open) {
      state.value = props.initialState
      void load()
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => generation++)
</script>

<template>
  <RecordSurface :open="open" title="我的流程草稿与提交材料" @update:open="emit('update:open', $event)">
    <div class="flow-work-index">
      <p class="flow-work-help">集中查看你在流程业务表单中暂存的草稿，以及已正式提交的材料。</p>
      <a-tabs :active-key="state" @change="changeState">
        <a-tab-pane :key="WorkDraftState.DRAFT" tab="待提交草稿" />
        <a-tab-pane :key="WorkDraftState.SUBMITTED" tab="已提交材料" />
      </a-tabs>
      <a-alert v-if="error" :message="error" type="error" show-icon class="flow-work-error">
        <template #action>
          <a-button :loading="loading" size="small" @click="load(retryAppend)">重试</a-button>
        </template>
      </a-alert>
      <OsTablePage
        :columns="columns"
        :data-source="items"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1050, y: 380 }"
        row-key="draftId"
        :title="isDraft ? '待提交草稿' : '已提交材料'"
        resizable
        show-column-settings
        column-settings-key="bpm-flow-work-index"
        :hidden-column-keys="['processInstanceId', 'taskId']"
      >
        <template #toolbar>
          <a-button :loading="loading" @click="load()">
            <ReloadOutlined />
            刷新
          </a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'applicationVersion'">V{{ record.applicationVersion }}</template>
          <template v-else-if="column.key === 'updatedAt'">{{ formatDateTime(record.updatedAt) }}</template>
          <template v-else-if="column.key === 'state'">
            <a-tag v-if="record.state === WorkDraftState.SUBMITTED" color="success">已提交</a-tag>
            <a-tag v-else-if="record.writable" color="processing">待提交</a-tag>
            <a-tooltip v-else :title="record.blockedReason || '当前无权修改'">
              <a-tag>只读</a-tag>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-tooltip
              :title="
                record.state === WorkDraftState.DRAFT && !record.writable
                  ? record.blockedReason || '当前无权办理'
                  : undefined
              "
            >
              <a-button
                type="link"
                size="small"
                :loading="navigating === record.draftId"
                :disabled="
                  (record.state === WorkDraftState.DRAFT && !record.writable) ||
                  (!!navigating && navigating !== record.draftId)
                "
                @click="openTask(record)"
              >
                <FormOutlined v-if="record.state === WorkDraftState.DRAFT && record.writable" />
                <EyeOutlined v-else />
                {{
                  record.state === WorkDraftState.SUBMITTED ? '查看材料' : record.writable ? '继续填写' : '暂不可办理'
                }}
              </a-button>
            </a-tooltip>
          </template>
        </template>
      </OsTablePage>
      <div class="flow-work-footer">
        <span v-if="items.length">已显示 {{ items.length }} 条</span>
        <span v-else-if="!loading && !error">
          {{
            before
              ? '本页暂无可显示记录，可继续加载。'
              : isDraft
                ? '暂无可查看的流程草稿。'
                : '暂无可查看的流程提交材料。'
          }}
        </span>
        <a-button v-if="before" :loading="loading" @click="load(true)">加载更多</a-button>
      </div>
    </div>
  </RecordSurface>
</template>

<style scoped>
.flow-work-index {
  min-width: 0;
}
.flow-work-help,
.flow-work-footer {
  color: var(--text-secondary, #64748b);
}
.flow-work-help {
  margin-bottom: 12px;
}
.flow-work-error {
  margin-bottom: 12px;
}
.flow-work-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
}
</style>
