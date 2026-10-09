<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { bindBusinessTask, inspectBusinessTasks, businessTaskTemplate, parseProcess } from '@/nocode/flow-task-binding'
import { errorMessage } from '@/nocode/data-center'
import { ResourceKind, type ApplicationRow } from '@/types/nocode/application'
import type { RuntimeApplication } from '@/types/nocode/runtime'

const props = defineProps<{ modelValue: string; processKey: string; processName: string; autoApprovalType?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const platform = useNocodePlatform()
const apps = ref<ApplicationRow[]>([]),
  release = ref<RuntimeApplication>(),
  appId = ref<string>(),
  formId = ref<string>(),
  nodeId = ref<string>(),
  loading = ref(false),
  error = ref('')
const editing = ref(false)
let applicationRequest = 0
const inspection = computed(() => {
  try {
    return { bindings: inspectBusinessTasks(props.modelValue, props.autoApprovalType ?? 0), error: '' }
  } catch (e) {
    return { bindings: [], error: errorMessage(e) }
  }
})
const nodes = computed(() => {
  try {
    return Array.from(parseProcess(props.modelValue).getElementsByTagNameNS('*', 'userTask')).map(n => ({
      value: n.getAttribute('id')!,
      label: n.getAttribute('name') || n.getAttribute('id')!
    }))
  } catch {
    return []
  }
})
const selectedBinding = computed(() => inspection.value.bindings.find(binding => binding.id === nodeId.value))
const canConfigure = computed(
  () => !selectedBinding.value || (selectedBinding.value.handler === 'nocode' && !!selectedBinding.value.configuration)
)
const forms = computed(
  () =>
    release.value?.definition.resources.filter(
      r => r.kind === ResourceKind.FORM && typeof r.config.objectId === 'string'
    ) || []
)
async function loadApplication() {
  const request = ++applicationRequest
  const current = appId.value
  release.value = undefined
  formId.value = undefined
  if (!current) return
  loading.value = true
  error.value = ''
  try {
    const result = await platform.runtime.application(current)
    if (request === applicationRequest && current === appId.value) release.value = result
  } catch (e) {
    if (request === applicationRequest && current === appId.value) error.value = errorMessage(e)
  } finally {
    if (request === applicationRequest && current === appId.value) loading.value = false
  }
}
function createTemplate() {
  try {
    emit('update:modelValue', businessTaskTemplate(props.processKey, props.processName))
    nodeId.value = 'business_work'
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function applyBinding() {
  try {
    const form = forms.value.find(f => f.id === formId.value),
      current = release.value
    if (!canConfigure.value || !form || !current || !nodeId.value) throw new Error('请选择支持的人工节点、应用和表单')
    emit(
      'update:modelValue',
      bindBusinessTask(props.modelValue, nodeId.value, {
        ...selectedBinding.value?.configuration,
        resource: {
          ...selectedBinding.value?.configuration?.resource,
          applicationId: current.application.id,
          applicationVersion: current.versionNo,
          applicationChecksum: current.checksum,
          resourceId: form.id,
          resourceKind: 'FORM'
        },
        objectId: String(form.config.objectId),
        operation: 'CREATE'
      })
    )
    message.success('已绑定表单，请保存并发布流程')
    editing.value = false
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
watch(
  nodes,
  value => {
    if (!value.some(node => node.value === nodeId.value))
      nodeId.value = value.length === 1 ? value[0]?.value : undefined
  },
  { immediate: true }
)
watch(nodeId, () => {
  applicationRequest++
  editing.value = false
  appId.value = undefined
  formId.value = undefined
  release.value = undefined
  loading.value = false
  error.value = ''
})
onMounted(async () => {
  try {
    apps.value = await platform.runtime.mine()
  } catch (e) {
    error.value = errorMessage(e)
  }
})
</script>
<template>
  <a-card title="任务办理配置" size="small" class="business-task-config">
    <p class="binding-hint">配置当前节点办理时使用的业务表单，支持暂存、恢复和提交新增记录；提交材料后完成任务。</p>
    <a-alert
      v-if="error || inspection.error"
      type="error"
      :message="error || inspection.error"
      show-icon
      class="binding-message"
    />
    <a-button v-if="!modelValue?.trim()" @click="createTemplate">创建单节点业务流程</a-button>
    <template v-else>
      <a-space wrap align="start" class="binding-picker">
        <a-select
          v-model:value="nodeId"
          aria-label="业务办理节点"
          placeholder="选择人工节点"
          :options="nodes"
          class="binding-select"
        />
        <a-button :disabled="!nodeId || !canConfigure" @click="editing = !editing">
          {{ editing ? '取消配置' : selectedBinding ? '更换绑定表单' : '配置业务办理' }}
        </a-button>
      </a-space>
      <a-descriptions v-if="selectedBinding?.configuration" bordered size="small" :column="2" class="binding-summary">
        <a-descriptions-item label="办理节点">{{ selectedBinding.name }}</a-descriptions-item>
        <a-descriptions-item label="办理动作">新增记录</a-descriptions-item>
        <a-descriptions-item label="业务应用">
          {{
            apps.find(a => a.id === selectedBinding?.configuration?.resource.applicationId)?.name ||
            selectedBinding.configuration.resource.applicationId
          }}
        </a-descriptions-item>
        <a-descriptions-item label="固定版本">
          V{{ selectedBinding.configuration.resource.applicationVersion }}
        </a-descriptions-item>
        <a-descriptions-item label="表单标识" :span="2">
          {{ selectedBinding.configuration.resource.resourceId }}
        </a-descriptions-item>
      </a-descriptions>
      <p v-else-if="nodeId && !selectedBinding" class="binding-hint">
        当前节点尚未绑定业务表单，沿用原有审批／办理方式。
      </p>
      <a-alert
        v-for="binding in inspection.bindings.filter(b => b.issues.length)"
        :key="binding.id"
        type="warning"
        :message="`${binding.name}：${binding.issues.join('；')}`"
        show-icon
        class="binding-message"
      />
      <div v-if="editing" class="binding-editor">
        <a-space wrap align="start">
          <a-select
            v-model:value="appId"
            aria-label="业务应用"
            placeholder="选择已发布应用"
            :options="apps.map(a => ({ value: a.id, label: a.name }))"
            class="binding-select"
            show-search
            option-filter-prop="label"
            @change="loadApplication"
          />
          <a-select
            v-model:value="formId"
            aria-label="业务表单"
            placeholder="选择业务表单"
            :loading="loading"
            :options="forms.map(f => ({ value: f.id, label: f.name }))"
            class="binding-select"
          />
          <a-button type="primary" :disabled="!nodeId || !formId || loading" @click="applyBinding">
            {{ selectedBinding ? '应用新绑定' : '绑定表单' }}
          </a-button>
        </a-space>
        <p v-if="release" class="binding-hint">
          将绑定 {{ release.application.name }} 的 V{{ release.versionNo }} 版本，办理动作：新增记录。
        </p>
      </div>
      <p v-if="inspection.bindings.length" class="binding-hint">
        已配置
        {{ inspection.bindings.length }} 个业务节点。保存不影响已发布流程，发布新版本后，在途任务仍使用原版本及原表单。
      </p>
    </template>
  </a-card>
</template>
<style scoped>
.business-task-config {
  margin-bottom: 16px;
}
.binding-hint {
  color: var(--text-secondary);
  margin: 12px 0;
  line-height: 1.6;
}
.binding-picker {
  margin-bottom: 12px;
}
.binding-select {
  width: 240px;
  max-width: 100%;
}
.binding-summary {
  overflow-wrap: anywhere;
  margin-bottom: 12px;
}
.binding-message {
  margin-bottom: 12px;
}
.binding-editor {
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fafafa;
}
</style>
