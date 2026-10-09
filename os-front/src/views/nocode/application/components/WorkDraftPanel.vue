<script setup lang="ts">
import { computed, ref, watch, provide, inject, onBeforeUnmount } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { v4 as uuid } from 'uuid'
import { nocodePlatformKey, useNocodePlatform } from '@/nocode/platform'
import { editorCloseKey } from '@/nocode/edit-boundary'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { businessFields, isRelationFieldId } from '@/nocode/business-fields'
import { boundFields } from '@/nocode/application-ui'
import { recordPayload } from '@/nocode/record-form'
import { editSignature } from '@/nocode/edit-signature'
import { errorMessage } from '@/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import { FieldType } from '@/types/nocode/enums'
import type { WorkDraftContext, SubmitWorkDraft } from '@/types/nocode/work'
import type { UiNode } from '@/types/nocode/application-ui'
import type { WorkApi } from '@/api/nocode/work'
import RecordForm from './RecordForm.vue'
import RecordReadView from './RecordReadView.vue'

const props = defineProps<{
  id: string
  task?: boolean
  workbench?: boolean
  api?: Pick<WorkApi, 'context' | 'saveDraft' | 'submit' | 'selection'>
}>()
const emit = defineEmits<{ changed: []; submitted: []; close: []; context: [value: WorkDraftContext] }>()
const platform = useNocodePlatform(),
  api = props.api || platform.work
// 所有嵌套选择器仍使用原组件，只将查询绑定到服务端保存的本人草稿。
provide(nocodePlatformKey, {
  ...platform,
  runtime: { ...platform.runtime, selection: q => api.selection(props.id, q) }
})
const context = ref<WorkDraftContext>(),
  values = ref<Record<string, unknown>>({}),
  loading = ref(false),
  busy = ref(false),
  operation = ref<'save' | 'submit' | ''>(''),
  validating = ref(false),
  error = ref(''),
  pending = ref<SubmitWorkDraft>(),
  baseRevision = ref<string | null>(null),
  form = ref<InstanceType<typeof RecordForm>>()
let generation = 0,
  initialInput = ''
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const signature = () => editSignature({ values: values.value, baseRevision: baseRevision.value })
const isDirty = () => !!context.value?.writable && !context.value.submission && signature() !== initialInput
useUnsavedNavigation(() => busy.value || isDirty())
async function requestClose() {
  if (busy.value) {
    message.info('正在处理，请稍候')
    return false
  }
  return confirmDiscard(isDirty())
}
const closeScope = inject(editorCloseKey, undefined)
closeScope?.add(requestClose)
onBeforeUnmount(() => {
  generation++
  closeScope?.delete(requestClose)
})
async function close() {
  // 独立办理页交给现有路由离开守卫确认，避免同一次返回连续弹出两次确认。
  if (props.workbench) {
    if (busy.value) message.info('正在处理，请稍候')
    else emit('close')
    return
  }
  if (await requestClose()) emit('close')
}
const fields = computed(() =>
  context.value
    ? businessFields(context.value.model.object).filter(
        f =>
          !isRelationFieldId(f.id!) &&
          context.value!.model.permissions.readFields.includes(f.id!) &&
          boundFields(context.value!.form.nodes).includes(f.id!)
      )
    : []
)
const readOnlyFields = (nodes: UiNode[]): string[] =>
  nodes.flatMap(n => [...(n.fieldId && n.presentation?.readOnly ? [n.fieldId] : []), ...readOnlyFields(n.children)])
const model = computed(
  () =>
    context.value && {
      ...context.value.model,
      writeFields: context.value.model.permissions.writeFields.filter(
        id => !readOnlyFields(context.value!.form.nodes).includes(id)
      )
    }
)
const conflict = computed(
  () => !!context.value?.currentRecord && baseRevision.value !== context.value.currentRecord.revision
)
const hasFileFields = computed(() =>
  fields.value.some(f => f.type === FieldType.ATTACHMENT || f.type === FieldType.IMAGE)
)
const renderNodes = computed(() => {
  const files = new Set(
    fields.value.filter(f => f.type === FieldType.ATTACHMENT || f.type === FieldType.IMAGE).map(f => f.id)
  )
  const visit = (nodes: UiNode[]): UiNode[] =>
    nodes.map(n => ({
      ...n,
      children: visit(n.children),
      presentation: files.has(n.fieldId) ? { ...n.presentation, readOnly: true } : n.presentation
    }))
  return visit(context.value?.form.nodes || [])
})
async function load() {
  const stamp = ++generation
  loading.value = true
  error.value = ''
  context.value = undefined
  values.value = {}
  initialInput = signature()
  try {
    const result = await api.context(props.id)
    if (stamp !== generation) return
    context.value = result
    values.value = clone(result.submission?.values || { ...result.currentRecord?.values, ...result.draft.values })
    baseRevision.value = result.draft.baseRecordRevision
    pending.value = undefined
    initialInput = signature()
    emit('context', result)
  } catch (e) {
    if (stamp === generation) error.value = errorMessage(e)
  } finally {
    if (stamp === generation) loading.value = false
  }
}
async function reload() {
  if (await requestClose()) await load()
}
function payload() {
  const c = context.value!
  return recordPayload(fields.value, c.model.object.fieldOptions, model.value!, !c.draft.recordId, values.value)
}
async function persist() {
  const c = context.value!
  if (!c.writable || conflict.value || pending.value) throw new Error('请先处理当前草稿的权限、版本或提交状态')
  const saved = await api.saveDraft({
    id: c.draft.id,
    expectedRevision: c.draft.revision,
    resource: c.draft.resource,
    objectId: c.draft.objectId,
    recordId: c.draft.recordId,
    baseRecordRevision: baseRevision.value,
    values: payload()
  })
  c.draft = saved
  // 仅更新本次写入的规范值，不拿新建默认值覆盖恢复内容。
  values.value = { ...values.value, ...saved.values }
  initialInput = signature()
  emit('changed')
  emit('context', c)
}
async function save() {
  if (busy.value) return
  busy.value = true
  operation.value = 'save'
  error.value = ''
  try {
    await persist()
    message.success('草稿已暂存，业务记录尚未变更')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
    operation.value = ''
  }
}
async function submit() {
  if (busy.value) return
  busy.value = true
  operation.value = 'submit'
  error.value = ''
  try {
    if (!pending.value) {
      if (!form.value) throw new Error('表单尚未准备完成')
      validating.value = true
      try {
        await form.value.validate()
      } finally {
        validating.value = false
      }
      if (isDirty()) await persist()
      const draft = context.value!.draft
      pending.value = { draftId: draft.id, expectedRevision: draft.revision, idempotencyKey: uuid() }
    }
    // 网络失败时保留同一个命令，禁止先改稿再换标识重复提交。
    await api.submit(pending.value)
    pending.value = undefined
    message.success(props.task ? '任务已完成，业务记录和提交材料已保存' : '已正式提交，业务记录与本次材料已保存')
    emit('changed')
    emit('submitted')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
    operation.value = ''
  }
}
async function rebase(useLatest: boolean) {
  const c = context.value
  if (!c?.currentRecord || busy.value || pending.value) return
  const accepted = await new Promise<boolean>(resolve =>
    Modal.confirm({
      title: useLatest ? '用最新业务值替换当前填写？' : '保留草稿填写并采用最新记录版本？',
      content: '请先对照下方两份内容。仅处理当前表单有权修改的字段；暂存不会修改业务记录。',
      okText: '确认采用',
      cancelText: '继续对照',
      onOk: () => {
        resolve(true)
      },
      onCancel: () => {
        resolve(false)
      }
    })
  )
  if (!accepted) return
  if (useLatest) values.value = clone(c.currentRecord.values)
  baseRevision.value = c.currentRecord.revision
}
watch(() => props.id, load, { immediate: true })
</script>
<template>
  <a-spin :spinning="loading" :wrapper-class-name="workbench ? 'work-panel work-panel--workbench' : 'work-panel'">
    <a-alert v-if="error" type="error" :message="error" show-icon class="work-notice">
      <template #action><a-button size="small" :disabled="busy" @click="reload">重新读取</a-button></template>
    </a-alert>
    <template v-if="context && model">
      <div class="work-body">
        <div class="work-meta">
          <div>
            <h2>{{ context.formName }}</h2>
            <p>
              {{ context.model.object.objectName }} · 应用 V{{ context.draft.resource.applicationVersion }} ·
              {{ context.draft.recordId ? '修改记录' : '新增记录' }}
            </p>
          </div>
          <a-tag :color="context.submission ? 'success' : 'processing'">
            {{ context.submission ? '已提交' : '草稿' }}
          </a-tag>
        </div>
        <a-alert v-if="context.submission" type="success" show-icon class="work-notice" message="本次提交材料">
          <template #description>
            提交于
            {{
              formatDateTime(context.submission.submittedAt)
            }}。以下字段值保留提交时的内容；人员、关联记录等名称按当前可见信息显示。
          </template>
        </a-alert>
        <a-alert
          v-else-if="!context.writable"
          type="warning"
          :message="context.blockedReason || '当前草稿只读'"
          show-icon
          class="work-notice"
        />
        <a-alert
          v-else-if="pending"
          type="warning"
          show-icon
          class="work-notice"
          message="本次提交结果待确认"
          description="可以重试本次提交，或重新读取草稿确认结果。确认之前暂时不能修改内容。"
        />
        <a-alert
          v-else-if="conflict"
          type="warning"
          show-icon
          class="work-notice"
          message="业务记录已被修改"
          description="请对照最新业务记录和草稿填写，选择继续使用的内容后再暂存或提交。"
        />
        <p v-else-if="!context.submission" class="work-help">
          {{
            task
              ? '可先暂存未完成的填写。提交后将保存业务记录和本次材料，并完成当前流程任务。'
              : '可先暂存未完成的填写。正式提交将校验必填项，并保存业务记录和本次材料。'
          }}
        </p>
        <a-alert
          v-if="hasFileFields && !context.submission"
          type="info"
          class="work-notice"
          :message="
            task
              ? '当前流程办理暂不支持附件，请联系流程管理员调整表单。'
              : '当前阶段尚未支持附件草稿，附件字段在这里只读。需要上传文件时请使用业务表单的“保存记录”。'
          "
        />
        <div v-if="conflict && context.writable && !pending" class="work-compare">
          <details open>
            <summary>查看最新业务记录（下方表单为当前草稿填写）</summary>
            <RecordReadView
              :fields="fields"
              :values="context.currentRecord!.values"
              :options="context.model.object.fieldOptions"
              :nodes="renderNodes"
              :application-id="context.draft.resource.applicationId"
              :object-id="context.draft.objectId"
              :record-id="context.draft.recordId || undefined"
              :form-id="context.draft.resource.resourceId"
              :relations="context.model.object.relations"
              :business-policy="context.model.object.settings.businessFilePolicy || null"
            />
          </details>
          <a-space wrap>
            <a-button @click="rebase(false)">保留草稿填写并更新基础版本</a-button>
            <a-button @click="rebase(true)">采用最新业务值</a-button>
          </a-space>
        </div>
        <div :inert="(busy && !validating) || pending ? true : undefined" :aria-busy="busy">
          <RecordForm
            v-if="context.writable && !context.submission"
            ref="form"
            v-model="values"
            :fields="fields"
            :options="context.model.object.fieldOptions"
            :model="model"
            :creating="!context.draft.recordId"
            :nodes="renderNodes"
            :form-id="context.draft.resource.resourceId"
            :application-id="context.draft.resource.applicationId"
            :object-id="context.draft.objectId"
            :record-id="context.draft.recordId || undefined"
            :relations="context.model.object.relations"
            :layout="context.form.options?.layout"
            :business-policy="context.model.object.settings.businessFilePolicy || null"
          />
          <RecordReadView
            v-else
            :fields="fields"
            :values="values"
            :options="context.model.object.fieldOptions"
            :nodes="renderNodes"
            :form-id="context.draft.resource.resourceId"
            :application-id="context.draft.resource.applicationId"
            :object-id="context.draft.objectId"
            :record-id="context.submission?.recordId || context.draft.recordId || undefined"
            :relations="context.model.object.relations"
            :layout="context.form.options?.layout"
            :business-policy="context.model.object.settings.businessFilePolicy || null"
          />
        </div>
      </div>
      <div class="work-footer">
        <span v-if="!context.submission" class="work-help">
          {{ isDirty() ? '有尚未暂存的修改' : '已暂存于 ' + formatDateTime(context.draft.updatedAt) }}
        </span>
        <a-space wrap>
          <a-button :disabled="busy" @click="close">{{ workbench ? '返回流程' : '关闭' }}</a-button>
          <template v-if="context.writable && !context.submission">
            <a-button :loading="operation === 'save'" :disabled="busy || !!pending || conflict" @click="save">
              暂存草稿
            </a-button>
            <a-button type="primary" :loading="operation === 'submit'" :disabled="busy || conflict" @click="submit">
              {{ pending ? '重试本次提交' : task ? '提交并完成任务' : '正式提交' }}
            </a-button>
          </template>
        </a-space>
      </div>
    </template>
  </a-spin>
</template>
<style scoped>
.work-panel,
.work-body {
  min-width: 0;
}
.work-panel--workbench {
  height: 100%;
}
.work-panel--workbench :deep(> .ant-spin-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.work-panel--workbench .work-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 24px;
}
.work-panel--workbench .work-footer {
  flex-shrink: 0;
  margin-top: 0;
  padding: 16px 24px;
  background: var(--component-background, #fff);
}
.work-panel--workbench > :deep(.ant-spin-container > .work-notice) {
  margin: 16px 24px 0;
}
.work-panel--workbench .work-body :deep(.os-form-surface) {
  max-width: 100%;
  overflow-wrap: anywhere;
}
@media (max-width: 767px) {
  .work-panel--workbench .work-body {
    padding: 16px;
  }
  .work-panel--workbench .work-footer {
    padding: 12px 16px;
  }
}
.work-meta {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.work-meta h2 {
  margin: 0 0 8px;
  font-size: 20px;
}
.work-meta p,
.work-help {
  color: var(--text-secondary, #64748b);
  line-height: 1.7;
}
.work-notice {
  margin-bottom: 20px;
}
.work-compare {
  padding: 16px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  margin-bottom: 24px;
}
.work-compare summary {
  cursor: pointer;
  font-weight: 600;
  margin-bottom: 16px;
}
.work-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color, #e5e7eb);
}
</style>
