<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNocodePlatform } from '@/nocode/platform'
import { useApplicationRefresh } from '@/nocode/application-context'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
import { liveChangeTouches, useRecordLive } from '@/nocode/record-live'
import { isRecordMissing } from '@/nocode/save-rebase'
import { errorMessage } from '@/nocode/data-center'
import { businessFileField } from '@/nocode/business-file'
import { formatDateTime } from '@/utils/format'
import { FieldType } from '@/types/nocode/enums'
import { NodeKind, type FormConfig } from '@/types/nocode/application-ui'
import type { Aggregate, RecordModel } from '@/types/nocode/runtime'
import BusinessFileField from './BusinessFileField.vue'
const props = defineProps<{
  applicationId: string
  form: FormConfig
  recordId: string
  kind: string
  title?: string
  refreshKey?: number
}>()
const api = useNocodePlatform().runtime,
  router = useRouter(),
  refresh = useApplicationRefresh()
const model = ref<RecordModel>(),
  record = ref<Aggregate>(),
  loading = ref(false),
  error = ref('')
let generation = 0
const businessPolicy = computed(() => model.value?.object.settings.businessFilePolicy || null)
// 只使用授权后的当前记录附件 ID，不配置外部文件地址或跨对象查询。
const attachments = computed(() =>
  (model.value?.object.fields || []).filter(
    f =>
      (f.type === FieldType.ATTACHMENT || f.type === FieldType.IMAGE) &&
      record.value?.record.permissions?.readFields.includes(f.id!)
  )
)
const isBusinessField = (fieldId: string) => businessFileField(businessPolicy.value, fieldId)
const states: Record<string, string> = {
  RUNNING: '审批中',
  APPROVED: '审批通过',
  REJECTED: '未通过',
  CANCELED: '已取消'
}
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  model.value = undefined
  record.value = undefined
  try {
    const [nextModel, nextRecord] = await Promise.all([
      api.model(props.applicationId, props.form.objectId),
      api.get(props.applicationId, props.form.objectId, props.recordId)
    ])
    if (current !== generation) return
    model.value = nextModel
    record.value = nextRecord
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
/** 静默重取这条记录的附件与审批记录：不出骨架屏，内容没变不动。 */
async function refreshQuietly() {
  if (loading.value) return
  const current = generation
  const next = await api.get(props.applicationId, props.form.objectId, props.recordId, { quiet: true })
  if (current === generation && JSON.stringify(next) !== JSON.stringify(record.value)) record.value = next
}
useRuntimeDataRefresh({
  interest: () =>
    record.value
      ? { applicationId: props.applicationId, objectIds: [props.form.objectId], recordIds: [props.recordId] }
      : undefined,
  refresh: refreshQuietly
})
// 别人改了这条记录（附件、审批记录跟着变）：静默重取；记录被删时保留现有内容。
useRecordLive({
  applicationId: () => props.applicationId,
  objectIds: () => [props.form.objectId],
  reload: async change => {
    if (!liveChangeTouches(change, props.recordId) || change.deleted.has(props.recordId)) return
    try {
      await refreshQuietly()
    } catch (e) {
      if (!isRecordMissing(e)) throw e
    }
  }
})
watch(() => [props.applicationId, props.form.objectId, props.recordId, refresh.value, props.refreshKey], load, {
  immediate: true
})
</script>
<template>
  <a-card
    :title="title || (kind === NodeKind.ATTACHMENTS ? '记录附件' : '审批记录')"
    size="small"
    class="record-extras"
  >
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-skeleton v-if="loading" active :paragraph="{ rows: 2 }" />
    <template v-else-if="record && !error">
      <template v-if="kind === NodeKind.ATTACHMENTS">
        <div v-for="field in attachments" :key="field.id!" class="attachment-group">
          <h4>{{ field.name }}</h4>
          <BusinessFileField
            :model-value="(record.record.values[field.id!] || []) as string[]"
            :application-id="applicationId"
            :object-id="form.objectId"
            :record-id="recordId"
            :field-id="field.id!"
            :business-policy="businessPolicy"
            detailed
            disabled
          />
          <span
            v-if="!isBusinessField(field.id!) && !(record.record.values[field.id!] as unknown[])?.length"
            class="muted"
          >
            暂无文件
          </span>
        </div>
        <a-empty v-if="!attachments.length" description="当前记录没有可查看的附件字段" />
      </template>
      <a-list v-else-if="record.processes?.length" :data-source="record.processes">
        <template #renderItem="{ item }">
          <a-list-item>
            <a @click="router.push({ name: 'TaskInstanceDetail', query: { id: item.instanceId } })">{{ item.name }}</a>
            <a-space>
              <a-tag>{{ states[item.status] || item.status }}</a-tag>
              <span>{{ formatDateTime(item.createTime) }}</span>
            </a-space>
          </a-list-item>
        </template>
      </a-list>
      <a-empty v-else description="当前记录尚无审批记录" />
    </template>
  </a-card>
</template>
<style scoped>
.record-extras {
  margin-bottom: 16px;
  border-radius: 8px;
}
.attachment-group + .attachment-group {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #eef0f3;
}
.muted {
  color: #94a3b8;
  font-size: 12px;
}
</style>
