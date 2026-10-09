<script setup lang="ts">
import { computed, ref, watch, inject, provide, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { useApplicationRefresh, usePageRefresh, blockRefreshId } from '@/nocode/application-context'
import { resolvePageAction } from '@/nocode/page-actions'
import { applicationPagePath } from '@/nocode/runtime-navigation'
import { ResourceKind } from '@/types/nocode/application'
import { errorMessage } from '@/nocode/data-center'
import {
  NodeKind,
  PageActionKind,
  PageButtonType,
  RecordOpenMode,
  type UiNode,
  type PageConfig,
  type FormConfig
} from '@/types/nocode/application-ui'
import type { ApplicationResource } from '@/types/nocode/application'
import { BusinessAction } from '@/types/nocode/authorization'
import { RelationType } from '@/types/nocode/enums'
import { BusinessActionKind } from '@/types/nocode/business'
import type { RecordModel, Aggregate, RecordContext } from '@/types/nocode/runtime'
import RecordEditor from './RecordEditor.vue'
import RecordSurface from './RecordSurface.vue'
import { useInheritedReadOnly } from '@/nocode/record-read-only'
const PageRenderer = defineAsyncComponent(() => import('./PageRenderer.vue'))
const props = defineProps<{
  node: UiNode
  applicationId: string
  resources: ApplicationResource[]
  pageId?: string
  recordId?: string
}>()
const api = useNocodePlatform().runtime,
  router = useRouter(),
  refresh = useApplicationRefresh(),
  pageRefresh = usePageRefresh()
const depth = inject<number>('nocode-detail-depth', 0)
const inheritedReadOnly = useInheritedReadOnly()
provide('nocode-detail-depth', depth + 1)
const action = computed(() => props.node.action)
const page = computed(
  () => props.resources.find(r => r.id === props.pageId)?.config as unknown as PageConfig | undefined
)
const resolved = computed(() => resolvePageAction(props.node, page.value, props.resources))
const form = computed(() => resolved.value.form?.config as unknown as FormConfig | undefined)
const objectId = computed(() => String(form.value?.objectId || resolved.value.resource?.config.objectId || ''))
const requiredAction = computed(() =>
  action.value?.kind === PageActionKind.CREATE
    ? BusinessAction.CREATE
    : action.value?.kind === PageActionKind.EDIT
      ? BusinessAction.UPDATE
      : action.value?.kind === PageActionKind.EXECUTE_ACTION
        ? resolved.value.resource?.config.kind === BusinessActionKind.START_PROCESS
          ? BusinessAction.START_PROCESS
          : BusinessAction.UPDATE
        : BusinessAction.READ
)
const model = ref<RecordModel>(),
  record = ref<Aggregate>(),
  busy = ref(false),
  checking = ref(false),
  problem = ref(''),
  open = ref(false)
let generation = 0
const recordOperation = computed(() =>
  [PageActionKind.CREATE, PageActionKind.EDIT, PageActionKind.VIEW, PageActionKind.EXECUTE_ACTION].some(
    k => k === action.value?.kind
  )
)
const context = computed<RecordContext | undefined>(() =>
  resolved.value.target?.type === NodeKind.RELATED && props.pageId && props.recordId
    ? { pageId: props.pageId, nodeId: resolved.value.target.id, recordId: props.recordId }
    : undefined
)
const reference = computed(() =>
  model.value?.object.relations.find(r => r.id === resolved.value.target?.binding?.relationId)
)
const lockedValues = computed(() =>
  context.value && reference.value?.fieldId ? { [reference.value.fieldId]: context.value.recordId } : undefined
)
const disabledReason = computed(() => {
  if (!action.value) return '尚未配置点击动作'
  if (recordOperation.value && resolved.value.error) return resolved.value.error
  if (form.value?.options?.readOnly && requiredAction.value !== BusinessAction.READ) return '当前表单只读'
  if (inheritedReadOnly.value && requiredAction.value !== BusinessAction.READ) return '统计明细不允许编辑'
  if (checking.value) return '正在检查操作权限'
  if (problem.value) return problem.value
  if (
    depth >= 4 &&
    [PageActionKind.CREATE, PageActionKind.EDIT, PageActionKind.VIEW, PageActionKind.OPEN_PAGE].some(
      k => k === action.value?.kind
    )
  )
    return '请关闭部分窗口后再打开'
  if (action.value.targetNodeId && !resolved.value.target) return '目标区块不可用或无权访问'
  if (action.value.resourceId && !resolved.value.resource) return '目标资源不可用或无权访问'
  if (
    action.value.kind === PageActionKind.OPEN_PAGE &&
    resolved.value.resource?.config.contextObjectId &&
    !props.recordId
  )
    return '请先选择当前记录'
  if (!recordOperation.value) return ''
  if (!model.value) return '目标对象不可用'
  if ([PageActionKind.CREATE, PageActionKind.EDIT].some(k => k === action.value?.kind) && !model.value.writable)
    return '目标对象为只读'
  const permissions =
    action.value.kind === PageActionKind.CREATE ? model.value.permissions : record.value?.record.permissions
  if (!permissions?.actions.includes(requiredAction.value)) return '当前记录或对象未授予此操作权限'
  if (action.value.kind === PageActionKind.CREATE && resolved.value.target?.type === NodeKind.RELATED) {
    if (!context.value) return '请先选择当前记录'
    if (!reference.value?.fieldId || reference.value.kind === RelationType.MANY_TO_MANY) return '此关系不支持关联新增'
    if (!permissions.writeFields.includes(reference.value.fieldId)) return '没有所属字段的写入权限'
  }
  return ''
})
async function loadAccess() {
  const current = ++generation
  problem.value = ''
  model.value = undefined
  record.value = undefined
  checking.value = false
  if (!recordOperation.value) return
  checking.value = true
  try {
    if (resolved.value.error) throw new Error(resolved.value.error)
    if (!objectId.value) throw new Error('目标对象或表单未配置')
    const next = await api.model(props.applicationId, objectId.value)
    let currentRecord: Aggregate | undefined
    if (action.value?.kind !== PageActionKind.CREATE) {
      if (!props.recordId) throw new Error('请先选择当前记录')
      currentRecord = await api.get(props.applicationId, objectId.value, props.recordId)
    } else if (context.value && page.value?.contextObjectId) {
      await api.get(props.applicationId, page.value.contextObjectId, context.value.recordId)
    }
    if (current === generation) {
      model.value = next
      record.value = currentRecord
    }
  } catch (e) {
    if (current === generation) problem.value = errorMessage(e)
  } finally {
    if (current === generation) checking.value = false
  }
}
function confirm(text: string) {
  return new Promise<boolean>(resolve =>
    Modal.confirm({
      title: text,
      okText: '确认执行',
      cancelText: '取消',
      onOk: () => {
        resolve(true)
      },
      onCancel: () => {
        resolve(false)
      }
    })
  )
}
async function run() {
  if (busy.value || disabledReason.value) return
  busy.value = true
  try {
    // 点击时重新获取实时范围和记录版本，撤权/审批锁不能依赖旧页面的可见状态。
    if (recordOperation.value) await loadAccess()
    if (disabledReason.value) {
      message.warning(disabledReason.value)
      return
    }
    const a = action.value!
    if (a.confirmText && !(await confirm(a.confirmText))) return
    if (a.kind === PageActionKind.EXECUTE_ACTION) {
      if (!a.confirmText && !(await confirm(`执行“${props.node.text || '业务动作'}”？`))) return
      await api.action({
        applicationId: props.applicationId,
        objectId: objectId.value,
        actionId: a.resourceId!,
        recordId: record.value!.record.id!,
        expectedRevision: record.value!.record.revision!
      })
      message.success('业务动作已执行')
      refresh.value++
    } else if (a.kind === PageActionKind.REFRESH) {
      if (a.targetNodeId) {
        const key = blockRefreshId(props.pageId, props.recordId, a.targetNodeId)
        pageRefresh.value[key] = (pageRefresh.value[key] || 0) + 1
      } else refresh.value++
      message.success('已刷新')
    } else if (a.kind === PageActionKind.NAVIGATE) {
      if (resolved.value.resource?.kind === ResourceKind.MENU)
        await router.push({ path: '/nocode-app/runtime', query: { id: props.applicationId, menu: a.resourceId } })
      else await router.push(applicationPagePath(props.applicationId, a.resourceId!))
    } else open.value = true
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    busy.value = false
  }
}
const openPage = computed(() => (action.value?.kind === PageActionKind.OPEN_PAGE ? resolved.value.resource : undefined))
const openPageConfig = computed(() => openPage.value?.config as unknown as PageConfig | undefined)
function saved() {
  open.value = false
  refresh.value++
}
watch(
  () => [props.applicationId, props.pageId, props.recordId, props.node.action, refresh.value, open.value],
  () => {
    if (!open.value) void loadAccess()
  },
  { immediate: true }
)
</script>
<template>
  <a-tooltip :title="disabledReason || undefined">
    <span class="page-action">
      <a-button
        :type="
          node.display?.buttonType === PageButtonType.PRIMARY
            ? 'primary'
            : node.display?.buttonType === PageButtonType.TEXT
              ? 'text'
              : 'default'
        "
        :disabled="!!disabledReason"
        :loading="busy"
        @click="run"
      >
        {{ node.text || '按钮' }}
      </a-button>
    </span>
  </a-tooltip>
  <RecordSurface
    v-model:open="open"
    :title="openPage?.name || node.text || '业务操作'"
    :mode="action?.openMode || RecordOpenMode.DRAWER"
  >
    <PageRenderer
      v-if="openPage && openPageConfig"
      :nodes="openPageConfig.nodes"
      :page-id="openPage.id"
      :application-id="applicationId"
      :resources="resources"
      :record-id="openPageConfig.contextObjectId ? recordId : undefined"
    />
    <RecordEditor
      v-else-if="model && form"
      :application-id="applicationId"
      :model="model"
      :record="record"
      :form="form"
      :form-id="resolved.form?.id"
      :context="context"
      :locked-values="lockedValues"
      :read-only="action?.kind === PageActionKind.VIEW"
      :hide-footer="action?.kind === PageActionKind.VIEW"
      merge-on-conflict
      @saved="saved"
      @cancel="open = false"
    />
  </RecordSurface>
</template>
<style scoped>
.page-action {
  display: inline-block;
}
</style>
