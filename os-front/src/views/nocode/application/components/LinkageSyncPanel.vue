<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  isRuleChanged,
  linkageFieldKey,
  linkageFieldLabel,
  loopProblem,
  pendingCount,
  previewSummary,
  runBackfill,
  runPreview,
  type BackfillRun,
  type PreviewRun
} from '@/nocode/linkage-sync'
import type { LinkageSyncField, LinkageSyncOverview } from '@/types/nocode/linkage-sync'

/**
 * 应用设计器「自动更新」面板（2026-10-01 第一期契约 9.3）：列出应用当前发布版里开了「来源变化时自动更新」的字段。
 * 「检查」= 预告循环；「回填」= 先预告、确认「将更新 N 条」、再回填循环（带预告返回的签名）。
 * 翻页、停止、续跑都交给 nocode/linkage-sync.ts 的两个循环函数；游标只留在本组件的内存里，不落本地存储。
 */
const props = defineProps<{
  applicationId: string
  /** 应用当前发布版本；还没发布时为空。重新发布后据此重新读取。 */
  publishedVersion: number | null
  /** 所在页签是否正显示：离开时停止还在跑的循环。 */
  active: boolean
  /** 发布后带过来要突出显示的字段（「对象ID:字段ID」）。 */
  highlight?: string[]
}>()
const platform = useNocodePlatform()

const overview = ref<LinkageSyncOverview | null>(null),
  loading = ref(false),
  loadError = ref('')
interface RowState {
  phase: 'idle' | 'checking' | 'filling'
  /** 最近一次预告：进行中的进度或最终结果。 */
  preview: PreviewRun | null
  /** 最近一次回填：进行中的进度、停下时的累计与游标，或最终结果。 */
  backfill: BackfillRun | null
  /** 回填用的签名（来自确认前的那次预告）。 */
  signature: string
  problem: string
  conflict: boolean
  failuresOpen: boolean
}
const rows = reactive<Record<string, RowState>>({})
const stateOf = (field: LinkageSyncField): RowState =>
  (rows[linkageFieldKey(field)] ??= {
    phase: 'idle',
    preview: null,
    backfill: null,
    signature: '',
    problem: '',
    conflict: false,
    failuresOpen: false
  })
/** 正在处理的字段；同一时间只处理一个。 */
const busy = ref('')
const confirming = ref<{ field: LinkageSyncField; run: PreviewRun } | null>(null)
let controller: AbortController | null = null,
  loadTurn = 0

function stop() {
  controller?.abort()
}
async function load() {
  const turn = ++loadTurn
  stop()
  loadError.value = ''
  if (props.publishedVersion == null || !props.applicationId) {
    overview.value = null
    return
  }
  loading.value = true
  try {
    const result = await platform.applications.linkageOverview(props.applicationId, 'PUBLISHED')
    if (turn !== loadTurn) return
    overview.value = result
  } catch (e) {
    if (turn === loadTurn) loadError.value = errorMessage(e)
  } finally {
    if (turn === loadTurn) loading.value = false
  }
}
watch(
  () => [props.applicationId, props.publishedVersion] as const,
  () => {
    // 换了应用或重新发布：规则可能变了，之前的检查结果和回填进度全部作废。
    for (const key of Object.keys(rows)) delete rows[key]
    confirming.value = null
    overview.value = null
    void load()
  },
  { immediate: true }
)
watch(
  () => props.active,
  active => {
    if (!active) stop()
  }
)
onBeforeUnmount(() => {
  loadTurn++
  stop()
})

const target = (field: LinkageSyncField) => ({
  applicationId: props.applicationId,
  targetObjectId: field.targetObjectId,
  targetFieldId: field.targetFieldId
})
/** 在一个字段上跑一段循环：登记为忙、准备中止信号，结束后放开。 */
async function during(field: LinkageSyncField, phase: RowState['phase'], work: (signal: AbortSignal) => Promise<void>) {
  const state = stateOf(field)
  if (busy.value) return
  busy.value = linkageFieldKey(field)
  controller = new AbortController()
  state.phase = phase
  state.problem = ''
  state.conflict = false
  try {
    await work(controller.signal)
  } finally {
    state.phase = 'idle'
    busy.value = ''
    controller = null
  }
}
/** 预告到最后一页；只有翻完才返回结果，否则把原因记在这一行上。 */
async function preview(field: LinkageSyncField, signal: AbortSignal): Promise<PreviewRun | null> {
  const state = stateOf(field)
  state.preview = null
  state.backfill = null
  state.failuresOpen = false
  try {
    const run = await runPreview(
      platform.applications,
      { ...target(field), basis: 'PUBLISHED' },
      { signal, onProgress: progress => (state.preview = progress) }
    )
    state.preview = run
    if (run.status === 'done') return run
    state.problem = loopProblem(run)
  } catch (e) {
    state.preview = null
    if (isRuleChanged(e)) state.conflict = true
    else state.problem = errorMessage(e)
  }
  return null
}
const check = (field: LinkageSyncField) =>
  during(field, 'checking', async signal => {
    await preview(field, signal)
  })
/** 回填第一步：先预告。结果为 0 就是「已是最新」，不用回填；否则弹确认框。 */
const prepareBackfill = (field: LinkageSyncField) =>
  during(field, 'checking', async signal => {
    const run = await preview(field, signal)
    if (run && pendingCount(run) > 0) confirming.value = { field, run }
  })
function fill(field: LinkageSyncField, cursor: string | null, initial: BackfillRun | null) {
  const state = stateOf(field)
  return during(field, 'filling', async signal => {
    state.failuresOpen = false
    try {
      const run = await runBackfill(
        platform.applications,
        { ...target(field), signature: state.signature },
        { signal, cursor, initial, onProgress: progress => (state.backfill = progress) }
      )
      state.backfill = run
      state.problem = run.status === 'failed' ? loopProblem(run) : ''
    } catch (e) {
      if (isRuleChanged(e)) {
        // 预告之后规则又变了：之前的预告与进度都不能再用。
        state.conflict = true
        state.preview = null
        state.backfill = null
      } else state.problem = errorMessage(e)
    }
  })
}
function confirmBackfill() {
  const pending = confirming.value
  if (!pending) return
  confirming.value = null
  const state = stateOf(pending.field)
  state.signature = pending.run.signature
  state.backfill = null
  void fill(pending.field, null, null)
}
const resume = (field: LinkageSyncField) => {
  const stopped = stateOf(field).backfill
  if (stopped) void fill(field, stopped.cursor, stopped)
}

/** 回填停在半路（被停止或某一页失败），可以继续。 */
const resumable = (field: LinkageSyncField) => {
  const state = stateOf(field)
  return state.phase === 'idle' && !state.conflict && !!state.backfill && state.backfill.status !== 'done'
}
const total = (state: RowState) => (state.preview?.total == null ? '' : ` / ${state.preview.total}`)
const counts = (run: BackfillRun) => `已更新 ${run.updated} 条 · 失败 ${run.failedCount} 条`
function statusText(field: LinkageSyncField): string {
  const state = stateOf(field)
  if (state.phase === 'checking') return `正在检查…已检查 ${state.preview?.scanned ?? 0}${total(state)} 条`
  if (state.phase === 'filling')
    return `正在回填…已处理 ${state.backfill?.scanned ?? 0}${total(state)} 条 · ${counts(
      state.backfill ?? {
        status: 'running',
        cursor: null,
        scanned: 0,
        updated: 0,
        unchanged: 0,
        failedCount: 0,
        failed: []
      }
    )}`
  if (state.conflict) return '规则已变化，请重新检查'
  const backfill = state.backfill
  if (backfill)
    return backfill.status === 'done'
      ? `回填完成：已处理 ${backfill.scanned} 条 · ${counts(backfill)}`
      : `已停止：已处理 ${backfill.scanned}${total(state)} 条 · ${counts(backfill)}${state.problem ? `（${state.problem}）` : ''}`
  const checked = state.preview
  if (checked?.status === 'done') {
    if (pendingCount(checked) > 0) return previewSummary(checked)
    return checked.failedCount ? `已是最新（另有 ${checked.failedCount} 条无法求值）` : '已是最新'
  }
  return state.problem ? `检查未完成：${state.problem}` : ''
}
/** 可展开的明细：回填看失败原因，检查看无法求值的原因。 */
const failures = (field: LinkageSyncField) => {
  const state = stateOf(field)
  if (state.phase !== 'idle') return []
  return (state.backfill ?? state.preview)?.failed ?? []
}
const failureCount = (field: LinkageSyncField) => {
  const state = stateOf(field)
  return (state.backfill ?? state.preview)?.failedCount ?? 0
}
/**
 * 这次打开面板以来已经确认「已有的记录都是最新」：检查结果为 0，或回填跑完且没有失败。
 * 这时不再显示「已有的记录还没有按这条规则更新」那句提示（否则回填完了还在催人回填）。
 */
const settled = (field: LinkageSyncField) => {
  const state = stateOf(field)
  if (state.phase !== 'idle' || state.conflict) return false
  if (state.backfill) return state.backfill.status === 'done' && !state.backfill.failedCount
  const checked = state.preview
  return checked?.status === 'done' && pendingCount(checked) === 0 && !checked.failedCount
}
const removedText = computed(() => (overview.value?.removed ?? []).map(linkageFieldLabel).join('、'))
</script>
<template>
  <section class="linkage-sync" aria-label="自动更新">
    <p class="linkage-sync-help">
      这里列出开启了「来源变化时自动更新」的字段。新开启或改过规则后，已有的记录不会自动补上，需要在这里「回填」一次；之后来源记录一变，系统会自动更新。
    </p>
    <a-empty v-if="publishedVersion == null" description="应用发布后，这里会列出开启了自动更新的字段。" />
    <template v-else>
      <a-alert v-if="loadError" type="error" show-icon :message="`未能读取自动更新字段：${loadError}`">
        <template #action><a-button size="small" @click="load">重新加载</a-button></template>
      </a-alert>
      <a-spin v-else-if="loading && !overview" />
      <template v-else-if="overview">
        <a-empty
          v-if="!overview.fields.length"
          description="这个应用里还没有开启自动更新的字段。在数据对象的字段里配置数据联动并打开「来源变化时自动更新」，发布数据对象、在本应用同步对象版本并发布应用后生效。"
        />
        <article
          v-for="field in overview.fields"
          :key="linkageFieldKey(field)"
          class="linkage-sync-row"
          :class="{ 'linkage-sync-highlight': highlight?.includes(linkageFieldKey(field)) }"
        >
          <header>
            <strong>{{ linkageFieldLabel(field) }}</strong>
            <a-tag v-if="field.change === 'NEW'" color="blue">新开启</a-tag>
            <a-tag v-else-if="field.change === 'CHANGED'" color="orange">规则有变化</a-tag>
            <span class="linkage-sync-muted">来源：{{ field.sourceObjectName }}</span>
          </header>
          <p v-if="field.change !== 'UNCHANGED' && !settled(field)" class="linkage-sync-muted">
            已有的记录还没有按这条规则更新：请先「检查」，再「回填」。
          </p>
          <p v-if="field.divergent.length" class="linkage-sync-warning">
            以下应用还没有同步到同一规则，经它们保存「{{ field.sourceObjectName }}」时不会按本规则更新：{{
              field.divergent.map(item => item.applicationName).join('、')
            }}
          </p>
          <p v-if="statusText(field)" class="linkage-sync-status" aria-live="polite">{{ statusText(field) }}</p>
          <ul v-if="stateOf(field).failuresOpen && failures(field).length" class="linkage-sync-failures">
            <li v-for="item in failures(field)" :key="item.recordId">记录 {{ item.recordId }}：{{ item.reason }}</li>
            <li v-if="failureCount(field) > failures(field).length" class="linkage-sync-muted">
              共 {{ failureCount(field) }} 条，这里只列出前 {{ failures(field).length }} 条。
            </li>
          </ul>
          <a-space>
            <a-button v-if="busy === linkageFieldKey(field)" danger @click="stop">停止</a-button>
            <template v-else-if="resumable(field)">
              <a-button type="primary" :disabled="!!busy" @click="resume(field)">继续</a-button>
              <a-button :disabled="!!busy" @click="prepareBackfill(field)">重新开始</a-button>
            </template>
            <template v-else>
              <a-button :disabled="!!busy" @click="check(field)">检查</a-button>
              <a-button type="primary" :disabled="!!busy" @click="prepareBackfill(field)">回填</a-button>
            </template>
            <a-button
              v-if="failures(field).length"
              type="link"
              @click="stateOf(field).failuresOpen = !stateOf(field).failuresOpen"
            >
              {{
                stateOf(field).failuresOpen ? '收起' : stateOf(field).backfill ? '查看失败原因' : '查看无法求值的原因'
              }}
            </a-button>
          </a-space>
        </article>
        <p v-if="removedText" class="linkage-sync-muted">以下字段不再自动更新，已有的值保留：{{ removedText }}</p>
        <a-button v-if="overview.fields.length" type="link" :disabled="!!busy || loading" @click="load">
          刷新列表
        </a-button>
      </template>
    </template>
    <a-modal
      :open="!!confirming"
      title="回填已有记录"
      ok-text="开始回填"
      cancel-text="取消"
      destroy-on-close
      @ok="confirmBackfill"
      @cancel="confirming = null"
    >
      <template v-if="confirming">
        <p>「{{ linkageFieldLabel(confirming.field) }}」{{ previewSummary(confirming.run) }}。</p>
        <p v-if="confirming.run.failedCount">无法求值的 {{ confirming.run.failedCount }} 条会保持原值。</p>
        <p class="linkage-sync-muted">
          系统会按现在的数据重新计算并保存这些记录；回填期间列表照常可用，可以随时停止，之后再继续。
        </p>
      </template>
    </a-modal>
  </section>
</template>
<style scoped>
.linkage-sync {
  display: grid;
  gap: 12px;
}
.linkage-sync-help {
  margin: 0;
  color: var(--text-secondary, #64748b);
}
.linkage-sync-row {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.linkage-sync-row header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.linkage-sync-row p {
  margin: 0;
  line-height: 1.7;
}
.linkage-sync-highlight {
  border-color: var(--ant-color-primary, #1677ff);
  background: var(--primary-bg, #f0f5ff);
}
.linkage-sync-muted {
  color: var(--text-secondary, #64748b);
  font-size: 13px;
}
.linkage-sync-warning {
  color: var(--ant-color-warning, #d48806);
  font-size: 13px;
}
.linkage-sync-status {
  font-weight: 500;
}
.linkage-sync-failures {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}
</style>
