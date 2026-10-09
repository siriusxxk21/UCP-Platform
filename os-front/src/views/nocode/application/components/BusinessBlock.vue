<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ViewConfig, FormConfig } from '@/types/nocode/application-ui'
import type { RecordModel, Aggregate, RecordContext } from '@/types/nocode/runtime'
import { BusinessAction } from '@/types/nocode/authorization'
import BusinessRecords from './BusinessRecords.vue'
import RecordEditor from './RecordEditor.vue'
import { useApplicationRefresh } from '@/nocode/application-context'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
import { liveChangeTouches, useRecordLive } from '@/nocode/record-live'
import { isRecordMissing } from '@/nocode/save-rebase'
import { useInheritedReadOnly } from '@/nocode/record-read-only'
const readOnly = useInheritedReadOnly()
const refresh = useApplicationRefresh()
const props = defineProps<{
  applicationId: string
  resources: ApplicationResource[]
  resourceId: string
  metric?: boolean
  recordId?: string
  initialRecordId?: string
  context?: RecordContext
  detail?: boolean
  title?: string
  standalone?: boolean
  refreshKey?: number
  /** 列表查询栏的外部挂载点（页签行右侧）；只有页签里唯一的列表会拿到。 */
  searchTarget?: HTMLElement | null
}>()
const api = useNocodePlatform().runtime
const resource = computed(() => props.resources.find(r => r.id === props.resourceId))
const view = computed(() =>
  resource.value?.kind === ResourceKind.VIEW ? (resource.value.config as unknown as ViewConfig) : undefined
)
const form = computed(() =>
  resource.value?.kind === ResourceKind.FORM ? (resource.value.config as unknown as FormConfig) : undefined
)
const model = ref<RecordModel>(),
  record = ref<Aggregate>(),
  error = ref(''),
  total = ref<number>(),
  loading = ref(false),
  savedId = ref(''),
  editor = ref<InstanceType<typeof RecordEditor>>()
const editingDetail = ref(false)
// 只读详情的这条记录被别人删了：内容留着，标出来，不再提供「编辑资料」。
const recordDeleted = ref(false)
async function load() {
  loading.value = true
  error.value = ''
  model.value = undefined
  total.value = undefined
  record.value = undefined
  savedId.value = ''
  editingDetail.value = false
  recordDeleted.value = false
  try {
    if (!resource.value) throw new Error('页面绑定资源不存在')
    if (form.value) {
      model.value = await api.model(props.applicationId, form.value.objectId)
      if (props.recordId) record.value = await api.get(props.applicationId, form.value.objectId, props.recordId)
    }
    if (props.metric && view.value)
      total.value = (
        await api.page({
          applicationId: props.applicationId,
          objectId: view.value.objectId,
          viewId: props.resourceId,
          pageNo: 1,
          pageSize: 1,
          descending: false
        })
      ).total
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
function saved(value: Aggregate) {
  if (props.detail) {
    record.value = value
    editingDetail.value = false
    refresh.value++
    return
  }
  savedId.value = value.record.id || ''
  record.value = value
  refresh.value++
}
function again() {
  savedId.value = ''
  record.value = undefined
  editor.value?.reset()
}
/** 静默重取用：失败不弹全局错误通知。 */
const pageQuietly = (query: Parameters<typeof api.page>[0]) => api.page(query, { quiet: true })
/** 静默重取只读内容（记录总数、只读详情）：不清空、不出 loading，内容没变不动。 */
async function refreshQuietly() {
  if (loading.value) return
  const applicationId = props.applicationId,
    resourceId = props.resourceId,
    recordId = props.recordId
  const current = () =>
    !loading.value &&
    applicationId === props.applicationId &&
    resourceId === props.resourceId &&
    recordId === props.recordId
  if (props.metric && view.value) {
    const next = (
      await pageQuietly({
        applicationId,
        objectId: view.value.objectId,
        viewId: resourceId,
        pageNo: 1,
        pageSize: 1,
        descending: false
      })
    ).total
    if (current() && next !== total.value) total.value = next
  } else if (props.detail && form.value && recordId) {
    const next = await api.get(applicationId, form.value.objectId, recordId, { quiet: true })
    // 取数期间用户点了「编辑资料」：不动它正在改的内容。
    if (current() && !editingDetail.value && JSON.stringify(next) !== JSON.stringify(record.value)) record.value = next
  }
}
useRuntimeDataRefresh({
  interest: () => {
    if (props.metric && view.value) return { applicationId: props.applicationId, objectIds: [view.value.objectId] }
    // 只有只读详情才重取；正在填写或编辑的表单不注册。
    if (props.detail && !editingDetail.value && form.value && props.recordId && record.value)
      return { applicationId: props.applicationId, objectIds: [form.value.objectId], recordIds: [props.recordId] }
    return undefined
  },
  refresh: refreshDetectingDeletion
})
/** 静默重取；只读详情的这条记录已经不存在时，留着内容并标出「已被删除」。 */
async function refreshDetectingDeletion() {
  if (recordDeleted.value) return
  const recordId = props.recordId
  try {
    await refreshQuietly()
  } catch (e) {
    if (!props.detail || !isRecordMissing(e)) throw e
    if (recordId === props.recordId && !editingDetail.value) recordDeleted.value = true
  }
}
// 别人改了数据：记录总数跟着重取；只读详情只在变更涉及这条记录时重取，正在「编辑资料」时不动。表单区不接。
useRecordLive({
  applicationId: () => props.applicationId,
  objectIds: () => {
    if (props.metric) return view.value ? [view.value.objectId] : []
    return props.detail && form.value && props.recordId ? [form.value.objectId] : []
  },
  reload: async change => {
    if (props.metric) return refreshQuietly()
    if (editingDetail.value || recordDeleted.value || !liveChangeTouches(change, props.recordId)) return
    if (props.recordId && change.deleted.has(props.recordId)) recordDeleted.value = true
    else await refreshDetectingDeletion()
  }
})
watch(() => [props.applicationId, props.resourceId, props.recordId], load, { immediate: true })
watch(
  () => [refresh.value, props.refreshKey],
  () => {
    // 刷新只读区块；正在填写的表单继续保留，提交时由后端校验记录版本。
    if (props.metric || (props.detail && !editingDetail.value)) void load()
  }
)
</script>
<template>
  <BusinessRecords
    v-if="view && !metric"
    :application-id="applicationId"
    :object-id="view.objectId"
    :initial-record-id="initialRecordId"
    :view-id="resourceId"
    :view="view"
    :resources="resources"
    :context="context"
    :refresh-key="refreshKey"
    :title="title || resource?.name"
    :standalone="standalone"
    :search-target="searchTarget"
  />
  <a-card v-else :title="title || resource?.name" size="small" class="business-block">
    <template
      v-if="
        detail &&
        !readOnly &&
        !form?.options?.readOnly &&
        record?.record.permissions?.actions.includes(BusinessAction.UPDATE) &&
        !recordDeleted &&
        !editingDetail
      "
      #extra
    >
      <a-button size="small" @click="editingDetail = true">编辑资料</a-button>
    </template>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-alert
      v-if="recordDeleted"
      type="warning"
      show-icon
      message="这条记录已被删除"
      class="business-block__notice"
      data-record-deleted
    />
    <a-spin :spinning="loading">
      <a-statistic v-if="metric" title="记录总数" :value="total ?? '—'" />
      <template v-else-if="form && model && !loading">
        <a-result v-if="savedId" status="success" title="保存成功" :sub-title="'记录编号：' + savedId">
          <template #extra>
            <a-button @click="again">继续新增</a-button>
            <a-button @click="savedId = ''">查看 / 修改这条记录</a-button>
          </template>
        </a-result>
        <RecordEditor
          v-else
          ref="editor"
          :application-id="applicationId"
          :model="model"
          :form="form"
          :form-id="resourceId"
          :record="record"
          :read-only="readOnly || (detail && !editingDetail)"
          :hide-footer="readOnly || (detail && !editingDetail)"
          merge-on-conflict
          @saved="saved"
          @cancel="detail ? (editingDetail = false) : again()"
        />
      </template>
    </a-spin>
  </a-card>
</template>
<style scoped>
.business-block {
  margin-bottom: 16px;
  width: 100%;
}
.business-block__notice {
  margin-bottom: 12px;
}
</style>
