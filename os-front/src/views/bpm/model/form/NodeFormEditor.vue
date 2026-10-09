<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getFormSimpleList, type BpmFormApi } from '@/api/bpm/form'
import { useNocodePlatform } from '@/nocode/platform'
import { editSignature } from '@/nocode/edit-signature'
import type { ApplicationRow } from '@/types/nocode/application'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import {
  bindingError,
  effectiveForm,
  formSourceSummary,
  materialReviewSummary,
  type FormSource,
  type NodeFormBinding
} from './node-form'

const props = defineProps<{ binding?: NodeFormBinding; inherited: FormSource; disabled?: boolean }>()
const emit = defineEmits<{ apply: [binding: NodeFormBinding, manual: boolean] }>()
const platform = useNocodePlatform()
const draft = ref<NodeFormBinding>({ mode: 'INHERIT' }),
  editing = ref(false),
  manual = ref(false)
const forms = ref<BpmFormApi.Form[]>([]),
  apps = ref<ApplicationRow[]>([]),
  release = ref<RuntimeApplication>()
const appId = ref<string>(),
  resourceId = ref<string>(),
  loading = ref(false),
  error = ref('')
let request = 0
const summary = computed(() => {
  const source = effectiveForm(props.binding, props.inherited)
  const text = formSourceSummary(source, forms.value)
  if (source?.kind !== 'APPLICATION_RESOURCE') return text
  const id = source.configuration?.resource.applicationId
  const name = apps.value.find(item => item.id === id)?.name
  return name ? text.replace(`应用 ${id}`, name) : text
})
const taskMode = computed(
  () => draft.value.taskMode || (draft.value.source?.kind === 'APPLICATION_RESOURCE' ? 'BUSINESS' : 'APPROVAL')
)
const appliedBusiness = computed(() => effectiveForm(props.binding, props.inherited)?.kind === 'APPLICATION_RESOURCE')
const reviewChoice = computed(() =>
  draft.value.materialReview?.scope === 'NONE' ? 'NONE' : draft.value.materialReview?.access || 'BUSINESS'
)
function reviewChanged(value: 'NONE' | 'TASK' | 'BUSINESS') {
  draft.value.materialReview = {
    scope: value === 'NONE' ? 'NONE' : 'PREVIOUS',
    access: value === 'NONE' ? draft.value.materialReview?.access || 'BUSINESS' : value
  }
}
function taskModeChanged(value: 'APPROVAL' | 'BUSINESS') {
  request++
  loading.value = false
  appId.value = undefined
  resourceId.value = undefined
  release.value = undefined
  error.value = ''
  manual.value = value === 'BUSINESS'
  draft.value =
    value === 'BUSINESS'
      ? {
          ...draft.value,
          taskMode: value,
          mode: 'OVERRIDE',
          source:
            props.binding?.source?.kind === 'APPLICATION_RESOURCE'
              ? JSON.parse(JSON.stringify(props.binding.source))
              : { kind: 'APPLICATION_RESOURCE' }
        }
      : { ...draft.value, taskMode: value, mode: 'INHERIT', source: undefined }
}
const resourceOptions = computed(() =>
  (release.value?.definition.resources || [])
    .filter(item => ['FORM', 'PAGE'].includes(item.kind))
    .map(item => ({
      value: item.id,
      label: item.name + (item.kind === 'PAGE' ? '（页面办理待接入）' : ''),
      disabled: item.kind !== 'FORM' || typeof item.config.objectId !== 'string'
    }))
)
function reset() {
  request++
  loading.value = false
  editing.value = false
  error.value = ''
  manual.value = false
  release.value = undefined
  appId.value = undefined
  resourceId.value = undefined
  draft.value = structuredClone(props.binding ? JSON.parse(JSON.stringify(props.binding)) : { mode: 'INHERIT' })
  manual.value = taskMode.value === 'BUSINESS'
}
// XML 重新解析会创建等值对象；只有已应用绑定实际变化时才同步，保留本地编辑会话。
watch(() => editSignature(props.binding), reset, { immediate: true })
function modeChanged(mode: 'INHERIT' | 'OVERRIDE') {
  manual.value = false
  const previous = props.binding?.source
  draft.value =
    mode === 'INHERIT'
      ? { ...draft.value, mode, source: undefined }
      : {
          ...draft.value,
          mode,
          source: previous && previous.kind !== 'APPLICATION_RESOURCE' ? previous : { kind: 'FLOW_FORM' }
        }
}
function sourceChanged(kind: FormSource['kind']) {
  manual.value = false
  request++
  loading.value = false
  release.value = undefined
  resourceId.value = undefined
  appId.value = undefined
  draft.value.source = { kind } as FormSource
}
async function loadApplication(value: string) {
  const sequence = ++request
  appId.value = value
  release.value = undefined
  resourceId.value = undefined
  loading.value = true
  error.value = ''
  try {
    const result = await platform.runtime.application(value)
    if (sequence === request) release.value = result
  } catch (e: any) {
    if (sequence === request) error.value = e.message || '应用加载失败'
  } finally {
    if (sequence === request) loading.value = false
  }
}
function selectResource(id: string) {
  const resource = release.value?.definition.resources.find(item => item.id === id)
  const current = release.value
  if (!current || !resource || resource.kind !== 'FORM') return
  resourceId.value = id
  const old = draft.value.source?.kind === 'APPLICATION_RESOURCE' ? draft.value.source.configuration : undefined
  draft.value.source = {
    kind: 'APPLICATION_RESOURCE',
    configuration: {
      ...old,
      resource: {
        ...old?.resource,
        applicationId: current.application.id,
        applicationVersion: current.versionNo,
        applicationChecksum: current.checksum,
        resourceId: resource.id,
        resourceKind: 'FORM'
      },
      objectId: String(resource.config.objectId),
      operation: 'CREATE'
    }
  }
}
function apply() {
  if (appId.value && draft.value.source?.kind === 'APPLICATION_RESOURCE' && !resourceId.value) {
    error.value = '请选择当前应用已发布的表单后再应用配置'
    return
  }
  error.value = bindingError(draft.value) || ''
  if (error.value || loading.value) return
  emit(
    'apply',
    JSON.parse(JSON.stringify(draft.value)),
    draft.value.mode === 'OVERRIDE' && draft.value.source?.kind === 'APPLICATION_RESOURCE' && manual.value
  )
  editing.value = false
}
onMounted(async () => {
  const results = await Promise.allSettled([getFormSimpleList(), platform.runtime.mine()])
  if (results[0].status === 'fulfilled') forms.value = results[0].value
  if (results[1].status === 'fulfilled') apps.value = results[1].value
  if (results.some(result => result.status === 'rejected'))
    error.value = '部分表单候选加载失败，请重新打开配置；原绑定保持不变'
})
</script>

<template>
  <div class="node-form-editor">
    <div class="binding-summary">
      <a-tag :color="appliedBusiness ? 'cyan' : 'purple'">{{ appliedBusiness ? '业务任务' : '审批节点' }}</a-tag>
      <a-tag>{{ !binding || binding.mode === 'INHERIT' ? '沿用发起表单' : '独立配置' }}</a-tag>
      <span>{{ summary }}</span>
    </div>
    <p class="binding-note">查阅材料：{{ materialReviewSummary(binding?.materialReview) }}</p>
    <a-button v-if="!editing" :disabled="disabled" @click="editing = true">配置节点表单</a-button>
    <template v-else>
      <a-form-item label="节点完成方式">
        <a-radio-group :value="taskMode" @change="taskModeChanged($event.target.value)">
          <a-radio value="APPROVAL">审批决定</a-radio>
          <a-radio value="BUSINESS">提交业务材料</a-radio>
        </a-radio-group>
      </a-form-item>
      <p class="binding-note">
        {{
          taskMode === 'BUSINESS'
            ? '业务任务通过填写并正式提交完成，当前支持单人新增记录。'
            : '审批节点通过同意或拒绝作出决定，可选审批时需要的表单。'
        }}
      </p>
      <a-radio-group v-if="taskMode === 'APPROVAL'" :value="draft.mode" @change="modeChanged($event.target.value)">
        <a-radio value="INHERIT">沿用发起表单</a-radio>
        <a-radio value="OVERRIDE">独立配置</a-radio>
      </a-radio-group>
      <p v-if="draft.mode === 'INHERIT'" class="binding-note">
        {{ formSourceSummary(inherited, forms) }}。发起表单修改后，本节点随之更新。
      </p>
      <template v-else>
        <a-form-item label="表单来源">
          <a-select :value="draft.source?.kind" aria-label="节点表单来源" @change="sourceChanged">
            <template v-if="taskMode === 'APPROVAL'">
              <a-select-option value="NONE">无需填写表单</a-select-option>
              <a-select-option value="FLOW_FORM">流程表单</a-select-option>
              <a-select-option value="SYSTEM_ROUTE" disabled>系统业务路由（节点办理待接入）</a-select-option>
            </template>
            <a-select-option v-else value="APPLICATION_RESOURCE">应用已发布表单／页面</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="draft.source?.kind === 'FLOW_FORM'" label="流程表单">
          <a-select
            v-model:value="draft.source.formId"
            aria-label="节点流程表单"
            :options="forms.map(item => ({ value: String(item.id), label: item.name }))"
            placeholder="请选择流程表单"
          />
        </a-form-item>
        <template v-if="draft.source?.kind === 'APPLICATION_RESOURCE'">
          <p class="binding-note">{{ formSourceSummary(draft.source) }}。选择应用和表单后才替换当前绑定。</p>
          <a-form-item label="业务应用">
            <a-select
              :value="appId"
              aria-label="节点业务应用"
              :options="apps.map(item => ({ value: item.id, label: item.name }))"
              placeholder="请选择业务应用"
              @change="loadApplication"
            />
          </a-form-item>
          <a-form-item label="已发布资源">
            <a-select
              :value="resourceId"
              aria-label="节点应用表单"
              :loading="loading"
              :disabled="!release"
              :options="resourceOptions"
              placeholder="请选择已发布的业务表单"
              @change="selectResource"
            />
          </a-form-item>
          <a-checkbox v-model:checked="manual" :disabled="taskMode === 'BUSINESS'">
            同时调整为人工单人办理，关闭节点跳过、同人跳过、无人自动处理和会签；保留人员选择
          </a-checkbox>
          <p class="binding-note">当前支持新增记录；填写、暂存和提交材料后完成任务。</p>
        </template>
      </template>
      <a-form-item label="查阅前序材料">
        <a-select :value="reviewChoice" aria-label="前序材料查阅权限" @change="reviewChanged">
          <a-select-option value="BUSINESS">按现有业务读取权限查阅</a-select-option>
          <a-select-option value="TASK">通过当前审批任务只读查阅</a-select-option>
          <a-select-option value="NONE">不查阅前序材料</a-select-option>
        </a-select>
      </a-form-item>
      <p class="binding-note">
        {{
          reviewChoice === 'TASK'
            ? '审批人可通过任务查阅前序已提交的普通级别有效业务字段，无需获得业务应用入口或编辑权限。流程表单仍遵守节点字段可见性；隐藏、敏感及附件内容不随材料开放。'
            : reviewChoice === 'NONE'
              ? '仅处理本节点的表单与审批意见，不提供前序材料阅读区。'
              : '同时校验审批任务资格和现有业务读取权限；旧配置默认沿用此规则。'
        }}
        本节点无需填写表单时，仍可独立配置材料查阅。保存并重新发布后对新实例生效。
      </p>
      <a-space>
        <a-button type="primary" :disabled="loading" @click="apply">应用节点配置</a-button>
        <a-button @click="reset">取消</a-button>
      </a-space>
    </template>
    <a-alert v-if="error" type="error" :message="error" show-icon />
  </div>
</template>
<style scoped>
.node-form-editor {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
}
.binding-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  overflow-wrap: anywhere;
}
.binding-note {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.node-form-editor :deep(.ant-form-item) {
  margin-bottom: 0;
}
</style>
