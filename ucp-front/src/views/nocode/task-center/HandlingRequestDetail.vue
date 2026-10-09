<script setup lang="ts">
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { computed, ref, watch } from 'vue'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import request from '@/utils/request'
import { createHandlingApi } from '@/api/nocode/handling'
import { handlingStateLabels, type HandlingDetail, type HandlingReopen } from '@/types/nocode/handling'
import HandlingResubmit from './HandlingResubmit.vue'
import { errorMessage } from '@/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import RecordReadView from '../application/components/RecordReadView.vue'
import BusinessFileField from '../application/components/BusinessFileField.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useNocodePlatform } from '@/nocode/platform'
import TaskDetail from './TaskDetail.vue'

const props = defineProps<{ id: string; taskId?: string; readOnly?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const api = createHandlingApi(request),
  router = useRouter()
const taskApi = useNocodePlatform().taskCenter
const linkedTaskId = ref('')
const linkedEntry = ref<{ entryKey: string; contributionId: string }>()
const detail = ref<HandlingDetail>(),
  busy = ref(false),
  error = ref(''),
  before = ref(false),
  withdrawing = ref(false),
  reason = ref('')
let generation = 0
const reopened = ref<HandlingReopen>(),
  resubmitEditor = ref<InstanceType<typeof HandlingResubmit>>()
async function reopen() {
  const token = generation
  busy.value = true
  error.value = ''
  try {
    const entry = await taskApi.entryHandlingLocation(props.id)
    if (token !== generation) return
    if (entry) {
      linkedTaskId.value = entry.taskId
      linkedEntry.value = entry
      return
    }
    const taskId = await taskApi.handlingTask(props.id)
    if (token !== generation) return
    if (taskId) linkedTaskId.value = taskId
    else {
      const next = await api.reopen(props.id)
      if (token === generation) reopened.value = next
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function closeReopen() {
  if (!resubmitEditor.value || (await resubmitEditor.value.canClose())) reopened.value = undefined
}
function resubmitted() {
  reopened.value = undefined
  emit('changed')
}
async function load() {
  const token = ++generation
  busy.value = true
  error.value = ''
  detail.value = undefined
  linkedTaskId.value = ''
  linkedEntry.value = undefined
  before.value = false
  try {
    const result = await api.detail(props.id, props.taskId)
    if (token === generation) detail.value = result
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) busy.value = false
  }
}
const values = computed(() =>
  before.value ? detail.value?.material.handling.before?.record.values || {} : detail.value?.material.values || {}
)
const groups = computed(() =>
  before.value ? detail.value?.material.handling.before?.details || {} : detail.value?.material.details || {}
)
function labels(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  values: Record<string, unknown>,
  snapshots?: Record<string, string>
) {
  return Object.fromEntries(
    fields
      .filter(f => !['IMAGE', 'ATTACHMENT'].includes(f.type))
      .map(f => {
        const value = values[f.id!],
          option = options[f.id!]
        const label = (v: unknown): string =>
          option?.options?.find(o => o.code === v)?.label ||
          (typeof v === 'boolean' ? (v ? '是' : '否') : typeof v === 'object' ? JSON.stringify(v) : String(v))
        return [
          f.id!,
          snapshots?.[f.id!] ||
            (value == null || value === ''
              ? f.type === 'AUTO_NUMBER' && !before.value
                ? '业务生效时生成'
                : '—'
              : Array.isArray(value)
                ? value.map(label).join('、')
                : label(value))
        ]
      })
  )
}
const mainLabels = computed(() =>
  detail.value
    ? labels(
        detail.value.definition.fields,
        detail.value.definition.fieldOptions,
        values.value,
        before.value ? detail.value.material.handling.before?.record.displayValues : detail.value.material.displayValues
      )
    : {}
)
async function withdraw() {
  if (!detail.value || !reason.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await api.withdraw(props.id, detail.value.request.revision, reason.value.trim())
    withdrawing.value = false
    message.success('申请已撤回')
    emit('changed')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function retry() {
  if (!detail.value) return
  busy.value = true
  error.value = ''
  try {
    await api.retry(props.id, detail.value.request.revision)
    emit('changed')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function process() {
  if (detail.value) router.push({ name: 'TaskInstanceDetail', query: { id: detail.value.request.processInstanceId } })
}
watch(() => [props.id, props.taskId], load, { immediate: true })
</script>
<template>
  <a-spin :spinning="busy">
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <template v-if="detail">
      <div class="request-heading">
        <div>
          <h3>{{ detail.request.name }}</h3>
          <span>
            {{ detail.request.applicationName }} · {{ detail.request.objectName }} ·
            {{ new Date(detail.request.submittedAt).toLocaleString() }}
          </span>
        </div>
        <a-tag>{{ handlingStateLabels[detail.request.status] }}</a-tag>
      </div>
      <a-alert v-if="detail.request.error" :message="detail.request.error" type="warning" show-icon class="notice" />
      <a-alert
        type="info"
        show-icon
        message="这里展示提交时的材料；审批中不会更新有效业务数据。变更前资料可切换查看。"
        class="notice"
      />
      <a-space v-if="!readOnly" wrap class="notice">
        <a-button
          v-if="detail.request.status === 'REJECTED' || detail.request.status === 'CANCELED'"
          type="primary"
          :loading="busy"
          @click="reopen"
        >
          修改后重新提交
        </a-button>
        <a-button @click="process">查看流程进度</a-button>
        <a-button
          v-if="detail.request.status === 'PENDING' || detail.request.status === 'APPLY_FAILED'"
          @click="withdrawing = true"
        >
          {{ detail.request.status === 'APPLY_FAILED' ? '放弃本次生效' : '撤回申请' }}
        </a-button>
        <a-button v-if="detail.request.status === 'APPLY_FAILED'" type="primary" :loading="busy" @click="retry">
          重试生效
        </a-button>
        <a-button :loading="busy" @click="load">刷新状态</a-button>
      </a-space>
      <a-radio-group v-if="detail.material.handling.before" v-model:value="before" class="notice">
        <a-radio-button :value="false">本次申请</a-radio-button>
        <a-radio-button :value="true">变更前资料</a-radio-button>
      </a-radio-group>
      <RecordReadView
        :fields="detail.definition.fields"
        :values="values"
        :options="detail.definition.fieldOptions"
        :display-values="mainLabels"
        :application-id="detail.request.applicationId"
        :object-id="detail.request.objectId"
        :record-id="detail.request.recordId || undefined"
        :business-policy="detail.definition.settings.businessFilePolicy || null"
        :relations="[]"
      />
      <section
        v-for="relation in detail.definition.relations.filter(r => r.kind === 'MANY_TO_MANY')"
        :key="relation.id!"
        class="request-details"
      >
        <h4>{{ relation.name }}</h4>
        <p>
          {{
            (before ? detail.material.handling.before?.record.displayValues : detail.material.displayValues)?.[
              'relation:' + relation.id
            ] ||
            (before ? detail.material.handling.before?.relations : detail.material.handling.relations)?.[
              relation.id!
            ]?.join('、') ||
            '—'
          }}
        </p>
      </section>
      <section
        v-for="group in detail.definition.details.filter(d => groups[d.id!])"
        :key="group.id!"
        class="request-details"
      >
        <h4>{{ group.name }}</h4>
        <OsTablePage
          :data-source="groups[group.id!] || []"
          :columns="group.fields.map(f => ({ title: f.name, dataIndex: f.id!, key: f.id!, width: 180 }))"
          :row-key="(row: any) => row.id || row.clientRowKey"
          :show-index="true"
          :pagination="{ pageSize: DEFAULT_PAGE_SIZE }"
        >
          <template #bodyCell="{ column, record }">
            <BusinessFileField
              v-if="group.fields.some(f => f.id === column.dataIndex && ['IMAGE', 'ATTACHMENT'].includes(f.type))"
              :model-value="record.values[column.dataIndex] || []"
              :image="group.fields.some(f => f.id === column.dataIndex && f.type === 'IMAGE')"
              :application-id="detail.request.applicationId"
              :object-id="detail.request.objectId"
              :record-id="detail.request.recordId || undefined"
              :detail-id="group.id!"
              :detail-record-id="record.id || undefined"
              :field-id="String(column.dataIndex)"
              :business-policy="detail.definition.settings.businessFilePolicy || null"
              disabled
            />
            <template v-else-if="column.dataIndex && column.dataIndex !== '_index'">
              {{
                labels(group.fields, group.fieldOptions, record.values, record.displayValues)[column.dataIndex] ||
                (Array.isArray(record.values[column.dataIndex])
                  ? `${record.values[column.dataIndex].length} 个附件`
                  : '—')
              }}
            </template>
          </template>
        </OsTablePage>
      </section>
    </template>
  </a-spin>
  <OsModalForm
    :open="withdrawing"
    display-mode="modal"
    :allow-switch-display="false"
    :resizable="false"
    :width="520"
    title="撤回申请"
    :loading="busy"
    ok-text="确认撤回"
    @cancel="withdrawing = false"
    @ok="withdraw"
  >
    <template #formItems>
      <p>撤回后保留申请材料，原业务数据保持不变。</p>
      <a-textarea v-model:value="reason" placeholder="请填写撤回原因" :maxlength="500" :rows="3" />
    </template>
  </OsModalForm>
  <OsModalForm
    display-mode="drawer"
    :allow-switch-display="false"
    :open="!!reopened"
    title="修改申请材料"
    width="min(1120px,96vw)"
    :show-footer="false"
    :destroy-on-close="true"
    :mask-closable="false"
    @cancel="closeReopen"
  >
    <template #formItems>
      <HandlingResubmit
        v-if="reopened && detail"
        ref="resubmitEditor"
        :application-id="detail.request.applicationId"
        :context="reopened"
        @close="resubmitted"
      />
    </template>
  </OsModalForm>
  <TaskDetail
    v-if="linkedTaskId"
    :id="linkedTaskId"
    initial-tab="business"
    :initial-entry-key="linkedEntry?.entryKey"
    :initial-contribution-id="linkedEntry?.contributionId"
    @close="linkedTaskId = ''"
    @select="linkedTaskId = $event"
    @changed="emit('changed')"
  />
</template>
<style scoped>
.request-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.request-heading h3 {
  margin: 0 0 8px;
}
.request-heading span {
  color: var(--text-secondary, #64748b);
}
.notice {
  margin-bottom: 16px;
}
.request-details {
  margin-top: 24px;
}
</style>
