<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { ResourceKind } from '@/types/nocode/application'
import type { Aggregate, RecordModel } from '@/types/nocode/runtime'
import type { FormConfig } from '@/types/nocode/application-ui'
import RecordEditor from './components/RecordEditor.vue'
import HandlingRequestDetail from '../task-center/HandlingRequestDetail.vue'

/** 既可嵌入底座审批详情，也可通过业务表单查看路径打开。服务端校验当前记录权限。 */
const props = defineProps<{ id?: string }>()
const handlingId = computed(() => {
  const key = props.id || String(route.query.id || '')
  return /^nocode-handling:[a-f0-9-]{36}$/.test(key) ? key.slice('nocode-handling:'.length) : undefined
})
const route = useRoute(),
  api = useNocodePlatform().runtime
const loading = ref(false),
  error = ref(''),
  record = ref<Aggregate>(),
  model = ref<RecordModel>(),
  applicationId = ref(''),
  form = ref<FormConfig>()
async function load() {
  if (handlingId.value) return
  loading.value = true
  error.value = ''
  model.value = undefined
  record.value = undefined
  try {
    const link = await api.processRecord(props.id || String(route.query.id || ''))
    applicationId.value = link.applicationId
    const [m, r, app] = await Promise.all([
      api.model(link.applicationId, link.objectId),
      api.get(link.applicationId, link.objectId, link.recordId),
      api.application(link.applicationId)
    ])
    model.value = m
    record.value = r
    form.value = app.definition.resources.find(r => r.kind === ResourceKind.FORM && r.config.objectId === link.objectId)
      ?.config as FormConfig | undefined
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
watch(() => [props.id, route.query.id], load, { immediate: true })
</script>
<template>
  <HandlingRequestDetail
    v-if="handlingId"
    :id="handlingId"
    :task-id="typeof route.query.taskId === 'string' ? route.query.taskId : undefined"
    read-only
  />
  <a-spin v-else :spinning="loading">
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <section v-else-if="model && record" class="process-record">
      <h3>{{ model.object.objectName }}</h3>
      <RecordEditor
        :application-id="applicationId"
        :model="model"
        :record="record"
        :form="form"
        read-only
        hide-footer
      />
    </section>
  </a-spin>
</template>
<style scoped>
.process-record {
  padding: 16px;
}
</style>
