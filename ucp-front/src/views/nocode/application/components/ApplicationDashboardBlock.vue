<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import {
  createApplicationDashboardTransport,
  applicationDashboardParameterReady
} from '@/nocode/application-dashboard-runtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ApplicationDashboardConfig } from '@/types/nocode/application-dashboard'
import type {
  ApplicationDashboardDrill,
  ApplicationDashboardInputValue,
  ApplicationDashboardModel
} from '@/types/nocode/application-dashboard-runtime'
import DashboardRuntime from '@/views/nocode/report-center/components/DashboardRuntime.vue'
import ApplicationDashboardParameters from './ApplicationDashboardParameters.vue'
import RecordSurface from './RecordSurface.vue'
import type { ViewConfig, FormConfig } from '@/types/nocode/application-ui'
const BusinessRecords = defineAsyncComponent(() => import('./BusinessRecords.vue'))
const props = defineProps<{
  applicationId: string
  resource: ApplicationResource
  resources?: ApplicationResource[]
  recordId?: string
  title?: string
  refreshKey?: number
}>()
const dashboard = ref<InstanceType<typeof DashboardRuntime>>()
const api = useNocodePlatform().applicationDashboards
const businessOpen = ref(false),
  businessDrill = ref<ApplicationDashboardDrill>(),
  reloadKey = ref(0)
const businessView = ref<ApplicationResource>()
const businessConfig = computed(() => businessView.value?.config as unknown as ViewConfig | undefined)
const businessForm = computed(
  () =>
    props.resources?.find(r => r.id === businessConfig.value?.formId && r.kind === ResourceKind.FORM)
      ?.config as unknown as FormConfig | undefined
)
function businessFault(cause: unknown) {
  if (dashboard.value?.runtimeFault(cause)) {
    businessOpen.value = false
    businessDrill.value = undefined
  }
}
function targetView(chartId: string) {
  const id = config.value.detailViews?.find(binding => binding.chartId === chartId)?.viewId
  return props.resources?.find(resource => resource.id === id && resource.kind === ResourceKind.VIEW)
}
const model = ref<ApplicationDashboardModel>(),
  parameters = ref<Record<string, ApplicationDashboardInputValue>>({})
const config = computed(() => model.value?.config || (props.resource.config as unknown as ApplicationDashboardConfig))
const parameterBindings = computed(() =>
  (config.value.inputBindings || []).filter(binding => binding.source === 'PARAMETER')
)
const parameterItems = computed(() =>
  parameterBindings.value.flatMap(binding => {
    const filter = model.value?.dashboard.content.filters?.find(filter => filter.id === binding.filterId)
    return filter && binding.parameter ? [{ name: binding.parameter, filter }] : []
  })
)
const needsRecord = computed(() => (config.value.inputBindings || []).some(binding => binding.source !== 'PARAMETER'))
const paused = computed(
  () =>
    (needsRecord.value && !props.recordId) ||
    parameterBindings.value.some(
      binding => !binding.parameter || !applicationDashboardParameterReady(parameters.value[binding.parameter])
    )
)
const contextKey = computed(() =>
  JSON.stringify([
    props.applicationId,
    props.resource.id,
    props.recordId,
    props.refreshKey,
    reloadKey.value,
    parameters.value
  ])
)
const transport = createApplicationDashboardTransport(
  api,
  () => ({
    applicationId: props.applicationId,
    resourceId: props.resource.id,
    recordId: config.value.contextObjectId ? props.recordId : undefined,
    parameters: parameters.value
  }),
  value => {
    businessOpen.value = false
    businessDrill.value = undefined
    model.value = value
  },
  {
    available: chartId => !!targetView(chartId),
    open: drill => {
      const view = targetView(drill.query.chartId)
      if (!view) throw new Error('当前业务视图不可用，请刷新应用')
      businessView.value = view
      businessDrill.value = drill
      businessOpen.value = true
    }
  }
)
watch(contextKey, () => {
  businessOpen.value = false
  businessDrill.value = undefined
})
watch(
  () => [props.applicationId, props.resource.id, props.recordId],
  () => {
    model.value = undefined
    parameters.value = {}
  }
)
</script>
<template>
  <DashboardRuntime
    ref="dashboard"
    class="application-dashboard"
    :transport="transport"
    :context-key="contextKey"
    :title="title || resource.name"
    :paused="paused"
    version-changed-message="应用看板配置已更新，请点击“刷新数据”重新加载。"
  >
    <template #inputs>
      <a-empty v-if="needsRecord && !recordId" description="请先从列表选择一条记录" />
      <ApplicationDashboardParameters
        v-if="parameterItems.length"
        :items="parameterItems"
        @apply="parameters = $event"
        @reset="parameters = {}"
      />
      <a-alert
        v-if="paused && !(needsRecord && !recordId)"
        type="info"
        message="请先填写必填看板参数，再查看分析结果。"
        show-icon
      />
    </template>
  </DashboardRuntime>
  <RecordSurface v-model:open="businessOpen" :title="(businessView?.name || '业务明细') + ' · 看板下钻'">
    <BusinessRecords
      v-if="businessView && businessConfig && businessDrill"
      :key="businessView.id"
      :application-id="applicationId"
      :object-id="businessConfig.objectId"
      :view-id="businessView.id"
      :view="businessConfig"
      :form="businessForm"
      :resources="resources"
      :dashboard-drill="businessDrill"
      @changed="reloadKey++"
      @fault="businessFault"
    />
  </RecordSurface>
</template>
<style scoped>
.application-dashboard {
  /* 应用页面已继承系统边距；看板仅保留筛选区与卡片内部间距。 */
  padding: 0;
  height: auto;
  min-width: 0;
}
</style>
