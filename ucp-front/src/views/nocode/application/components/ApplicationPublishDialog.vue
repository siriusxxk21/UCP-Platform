<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { collectPublishSharingIssues, type PublishSharingIssue } from '@/nocode/application-publish-check'
import { applicationPublishBlockReason, applicationRequiresPublishAndEnable } from '@/nocode/application-publish-mode'
import {
  LinkageRuleChangedError,
  changedLinkageFields,
  isLinkageForbidden,
  linkageFieldKey,
  linkageFieldLabel,
  loopProblem,
  previewSummary,
  runPreview,
  type PreviewRun
} from '@/nocode/linkage-sync'
import { useRuntimeApplicationCache } from '@/nocode/runtime-application-cache'
import type { ApplicationDetail, PublishedObject } from '@/types/nocode/application'
import type { LinkageSyncField, LinkageSyncOverview } from '@/types/nocode/linkage-sync'
import ObjectSharingPanel from '../../components/ObjectSharingPanel.vue'

const props = defineProps<{ open: boolean; detail?: ApplicationDetail; objects: Record<string, PublishedObject> }>()
const emit = defineEmits<{ cancel: []; published: [detail: ApplicationDetail]; showSharing: [] }>()
const platform = useNocodePlatform()
const runtimeDefinitions = useRuntimeApplicationCache()
const issues = ref<PublishSharingIssue[]>([])
const checking = ref(false),
  submitting = ref(false),
  checked = ref(false)
const checkError = ref(''),
  publishError = ref(''),
  reason = ref(''),
  reasonError = ref('')
const reasonInput = ref()
const managing = ref<PublishSharingIssue>()
const canManage = computed(() => platform.hasPermission('nocode:object:share'))
const incompatible = computed(() => props.detail?.issues || [])
const recovery = computed(() => !!props.detail?.application.recoveryPending)
/** 草稿里生效的「按日期自动执行」业务动作：发布时提示从发布当天起算、不回溯。 */
const dateRules = computed(() =>
  (props.detail?.draft.resources || []).filter(
    resource => resource.kind === 'AUTOMATION' && resource.config.mode === 'DATE' && resource.config.enabled !== false
  )
)
const publishAndEnable = computed(() => applicationRequiresPublishAndEnable(props.detail?.application))
const publishBlockReason = computed(() =>
  applicationPublishBlockReason(props.detail?.application, {
    publish: platform.hasPermission('nocode:app:publish'),
    manage: platform.hasPermission('nocode:app:manage')
  })
)
let checkRevision = 0

/**
 * 自动更新预告（第一期契约 9.3）：生效点是应用发布，所以在这里按草稿基准预告「将更新 N 条」。
 * 与发布前检查并行、互不影响：预告进行中、失败、超时都不进入发布按钮的可用条件，只显示「预告未完成」。
 */
interface LinkagePreviewRow {
  field: LinkageSyncField
  /** 还没开始为 null；之后是最近一次进度或最终结果。 */
  run: PreviewRun | null
  problem: string
}
const linkageOverview = ref<LinkageSyncOverview | null>(null),
  linkageRows = ref<LinkagePreviewRow[]>([]),
  linkageError = ref('')
let linkageTurn = 0,
  linkageAbort: AbortController | null = null
function stopLinkagePreview() {
  linkageTurn++
  linkageAbort?.abort()
  linkageAbort = null
}
async function previewLinkage() {
  stopLinkagePreview()
  linkageOverview.value = null
  linkageRows.value = []
  linkageError.value = ''
  const detail = props.detail
  // 总览与预告接口要求应用管理权限；没有权限时不调用，也不显示这一块。
  if (!detail || !props.open || !platform.hasPermission('nocode:app:manage')) return
  const turn = linkageTurn,
    controller = new AbortController(),
    applicationId = detail.application.id
  linkageAbort = controller
  const current = () => turn === linkageTurn
  try {
    const overview = await platform.applications.linkageOverview(applicationId, 'DRAFT')
    if (!current()) return
    linkageOverview.value = overview
    linkageRows.value = changedLinkageFields(overview).map(field => ({ field, run: null, problem: '' }))
  } catch (e) {
    // 不是应用创建者（接口 403）与没有管理权限同样处理：看不了预告，这一块不出现。
    if (current() && !isLinkageForbidden(e)) linkageError.value = errorMessage(e)
    return
  }
  // 逐个字段预告，翻到最后一页才算数；一个字段失败不影响下一个。
  for (const [index, row] of linkageRows.value.entries()) {
    if (!current()) return
    const update = (patch: Partial<LinkagePreviewRow>) => {
      if (current())
        linkageRows.value = linkageRows.value.map((item, position) =>
          position === index ? { ...item, ...patch } : item
        )
    }
    try {
      const run = await runPreview(
        platform.applications,
        {
          applicationId,
          basis: 'DRAFT',
          targetObjectId: row.field.targetObjectId,
          targetFieldId: row.field.targetFieldId
        },
        { signal: controller.signal, onProgress: progress => update({ run: progress }) }
      )
      update({ run, problem: loopProblem(run) })
    } catch (e) {
      update({
        problem: e instanceof LinkageRuleChangedError ? '规则刚刚有变化，请重新打开发布对话框' : errorMessage(e)
      })
    }
  }
}
function linkageRowText(row: LinkagePreviewRow): string {
  if (row.run?.status === 'done') return previewSummary(row.run)
  if (row.problem) return `预告未完成（${row.problem}）`
  const total = row.run?.total
  return `正在预告…已检查 ${row.run?.scanned ?? 0}${total == null ? '' : ` / ${total}`} 条`
}
const linkageDivergent = computed(() =>
  (linkageOverview.value?.fields ?? []).filter(field => field.divergent.length > 0)
)
const linkageRemoved = computed(() => linkageOverview.value?.removed ?? [])
/** 没有任何自动更新相关的内容时整段不出现。 */
const linkageVisible = computed(
  () =>
    !!linkageError.value ||
    linkageRows.value.length > 0 ||
    linkageDivergent.value.length > 0 ||
    linkageRemoved.value.length > 0
)

async function check() {
  const revision = ++checkRevision
  const detail = props.detail
  checked.value = false
  checkError.value = ''
  issues.value = []
  if (!detail) return false
  checking.value = true
  try {
    const grants = await platform.applications.sharing(detail.application.id)
    if (revision !== checkRevision || props.detail !== detail || !props.open) return false
    issues.value = collectPublishSharingIssues(
      detail.application.id,
      detail.application.name,
      detail.draft,
      props.objects,
      grants
    )
    checked.value = true
    return issues.value.length === 0
  } catch (e) {
    if (revision === checkRevision)
      checkError.value = `未能读取最新对象授权，暂时无法确认配置是否完整。请重新检查。${errorMessage(e)}`
    return false
  } finally {
    if (revision === checkRevision) checking.value = false
  }
}

async function closeManagement() {
  managing.value = undefined
  await check()
}
async function publish() {
  if (submitting.value || checking.value || !props.detail || publishBlockReason.value || incompatible.value.length)
    return
  if (!reason.value.trim()) {
    reasonError.value = '请填写发布说明，说明本次对业务使用者的变化'
    reasonInput.value?.focus()
    return
  }
  const detail = props.detail
  submitting.value = true
  const enableAfterPublish = publishAndEnable.value
  publishError.value = ''
  try {
    if (!(await check())) return
    const result = await (enableAfterPublish ? platform.applications.publishAndEnable : platform.applications.publish)({
      id: detail.application.id,
      expectedRevision: detail.application.revision,
      reason: reason.value
    })
    reason.value = ''
    // 本机刚发布：运行页缓存的旧定义作废，下次进入或切回时重新加载，不先闪旧内容。
    runtimeDefinitions.forget(detail.application.id)
    emit('published', result)
    message.success(enableAfterPublish ? '应用已发布并启用' : '应用已发布')
  } catch (e) {
    publishError.value = errorMessage(e)
    // 检查后被他人撤权时重新显示可操作的问题清单；其他服务端校验保留原始原因。
    await check()
  } finally {
    submitting.value = false
  }
}
watch(
  () => [props.open, props.detail?.application.id, props.detail?.application.revision],
  () => {
    checkRevision++
    checking.value = false
    managing.value = undefined
    checked.value = false
    publishError.value = ''
    reasonError.value = ''
    if (props.open) void check()
    // 关闭对话框时中止还在跑的预告；打开时重新预告。
    void previewLinkage()
  },
  { immediate: true }
)
watch(
  () => props.detail?.application.id,
  () => {
    reason.value = ''
  }
)
onScopeDispose(() => {
  checkRevision++
  stopLinkagePreview()
})
</script>

<template>
  <OsModalForm
    :open="open && !managing"
    :title="publishAndEnable ? '发布并启用应用' : '发布应用'"
    :width="760"
    :loading="submitting || checking"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    @cancel="!submitting && emit('cancel')"
  >
    <template #formItems>
      <div class="publish-check" aria-live="polite">
        <a-alert
          v-if="publishAndEnable"
          :type="publishBlockReason ? 'warning' : 'info'"
          show-icon
          :message="
            recovery && detail?.application.recoveryNeedsEdit
              ? '请先人工编辑并保存应用草稿'
              : '发布成功后将启用整个应用'
          "
          :description="
            recovery && detail?.application.recoveryNeedsEdit
              ? '完成保存后再发布启用；当前保持停用。'
              : publishBlockReason ||
                '所有成员将按原有权限恢复访问。系统重新校验配置、对象版本和共享授权；失败时保持停用。'
          "
        />
        <a-alert v-else-if="publishBlockReason" type="warning" show-icon :message="publishBlockReason" />
        <a-alert v-if="checkError" type="error" show-icon :message="checkError" />
        <a-alert
          v-if="incompatible.length"
          type="error"
          show-icon
          message="应用固定的数据对象版本与最新结构不兼容，无法发布"
        >
          <template #description>
            <ul class="publish-incompatible">
              <li v-for="issue in incompatible" :key="issue.objectId">
                {{ issue.objectName }}（固定 V{{ issue.versionNo }} · 最新 V{{ issue.latestVersionNo }}）：{{
                  issue.messages.join('；')
                }}
              </li>
            </ul>
            <p>请在“已引用对象”同步对象版本并调整相关资源后重新发布。</p>
          </template>
        </a-alert>
        <a-alert
          v-if="issues.length"
          type="error"
          show-icon
          :message="`发布前请修正以下 ${issues.length} 项配置`"
          description="请按下方提示补齐对象共享授权配置，然后重新检查。"
        />
        <article v-for="issue in issues" :key="issue.key" class="publish-issue">
          <h4>数据对象：{{ issue.objectName }}</h4>
          <p>
            <strong>问题：</strong>
            {{ issue.problem }}
          </p>
          <p>
            <strong>配置位置：</strong>
            应用中心 → 当前应用 → 已引用对象 → {{ issue.objectName }} → 配置数据权限
          </p>
          <p>
            <strong>如何修正：</strong>
            {{ issue.fix }}
          </p>
          <a-button v-if="canManage" @click="managing = issue">去配置共享授权</a-button>
          <p v-else class="muted">你没有共享授权管理权限，请将此对象名称、应用名称和缺失项交给数据管理员处理。</p>
        </article>
        <a-alert
          v-if="publishError && !issues.length"
          class="publish-server-error"
          type="error"
          show-icon
          message="应用尚未发布，请修正以下配置"
          :description="publishError"
        />
        <p v-if="checked && !issues.length && !publishError" class="muted">
          对象共享授权检查通过。发布时还会校验对象版本、页面等配置。
        </p>
        <section v-if="linkageVisible" class="publish-linkage" aria-label="自动更新预告">
          <p v-if="linkageError" class="muted">
            自动更新预告未完成：{{ linkageError }}。不影响发布；发布后可到「自动更新」里检查。
          </p>
          <template v-else>
            <template v-if="linkageRows.length">
              <h4>本次发布将开启或变更 {{ linkageRows.length }} 个自动更新字段</h4>
              <p v-for="row in linkageRows" :key="linkageFieldKey(row.field)">
                {{ linkageFieldLabel(row.field) }}：{{ linkageRowText(row) }}
              </p>
              <p>发布后请到「自动更新」里执行回填，存量记录才会更新。</p>
            </template>
            <p v-for="field in linkageDivergent" :key="'divergent:' + linkageFieldKey(field)">
              {{ linkageFieldLabel(field) }}：以下应用还没有同步到同一规则，经它们保存「{{
                field.sourceObjectName
              }}」时不会按本规则更新：{{ field.divergent.map(item => item.applicationName).join('、') }}
            </p>
            <p v-if="linkageRemoved.length">
              以下字段不再自动更新，已有的值保留：{{ linkageRemoved.map(linkageFieldLabel).join('、') }}
            </p>
          </template>
        </section>
        <a-space>
          <a-button :loading="checking" :disabled="submitting" @click="check">重新检查</a-button>
          <a-button v-if="issues.length && !canManage" @click="emit('showSharing')">返回已引用对象查看权限</a-button>
        </a-space>
      </div>
      <a-alert
        v-if="dateRules.length"
        class="publish-date-rules"
        type="info"
        show-icon
        message="按日期自动执行从发布当天起算，不回溯"
        :description="`本次发布包含 ${dateRules.length} 条按日期自动执行的业务动作（${dateRules
          .map(rule => rule.name)
          .join(
            '、'
          )}）。发布后从今天开始执行：日期为今天的记录会在几分钟内处理；发布前已经过去的日期不会补做。需要马上处理今天的记录，可在业务动作列表点「立即按今天执行」。`"
      />
      <a-form-item
        label="发布说明"
        required
        :validate-status="reasonError ? 'error' : undefined"
        :help="reasonError || undefined"
      >
        <a-textarea
          ref="reasonInput"
          v-model:value="reason"
          aria-label="发布说明"
          :rows="3"
          :maxlength="1000"
          :disabled="submitting"
          placeholder="说明本次对业务使用者的变化"
          @change="reasonError = ''"
        />
      </a-form-item>
    </template>
    <template #footer>
      <div class="publish-footer">
        <a-button :disabled="submitting" @click="emit('cancel')">取消</a-button>
        <a-button
          type="primary"
          :loading="submitting"
          :disabled="
            checking || !checked || !!checkError || !!issues.length || !!publishBlockReason || !!incompatible.length
          "
          @click="publish"
        >
          {{ publishAndEnable ? '发布并启用' : '发布应用' }}
        </a-button>
      </div>
    </template>
  </OsModalForm>
  <ObjectSharingPanel
    v-if="managing && detail"
    :key="managing.objectId"
    :object-id="managing.objectId"
    :application-id="detail.application.id"
    :application-name="detail.application.name"
    editor-only
    @closed="closeManagement"
  />
</template>

<style scoped>
.publish-check {
  display: grid;
  gap: 16px;
  margin: 8px 0 24px;
}
.publish-issue {
  padding: 16px;
  border: 1px solid #ffccc7;
  border-radius: 8px;
  background: #fffaf9;
  overflow-wrap: anywhere;
}
.publish-issue h4 {
  margin: 0 0 12px;
  font-size: 15px;
}
.publish-issue p {
  margin: 8px 0;
  line-height: 1.7;
}
.publish-date-rules {
  margin-bottom: 16px;
}
.publish-linkage {
  padding: 12px 16px;
  border: 1px solid #d6e4ff;
  border-radius: 8px;
  background: #f5f9ff;
  overflow-wrap: anywhere;
}
.publish-linkage h4 {
  margin: 0 0 8px;
  font-size: 15px;
}
.publish-linkage p {
  margin: 6px 0;
  line-height: 1.7;
}
.muted {
  color: #64748b;
}
.publish-incompatible {
  margin: 0 0 4px;
  padding-left: 18px;
}
.publish-incompatible + p {
  margin: 0;
}
.publish-server-error {
  white-space: pre-line;
  overflow-wrap: anywhere;
}
.publish-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
