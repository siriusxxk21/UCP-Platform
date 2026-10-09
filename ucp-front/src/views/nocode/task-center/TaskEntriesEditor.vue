<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { AppstoreOutlined, PlusOutlined, CloseOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { Modal } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import type { TaskNodeInput, TaskDataPolicy } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig, TaskWorkRule } from '@/types/nocode/task-work-entries'
import { taskEntryIdentity, type TaskEntryCandidate } from '@/nocode/task-entry-selection'
import {
  formatTaskWorkQuantity,
  taskWorkRuleSummary,
  taskWorkAdjustmentSummary,
  taskWorkRuleError,
  taskWorkRuleMinutes,
  taskWorkPlannedQuantityError
} from '@/nocode/task-work-rule'
import { formatEffectiveWorkMinutes } from '@/nocode/task-work-duration'
import { taskEntryScope, taskEntryScopeLabel } from '@/nocode/task-entry-scope'
import TaskEntrySelector from './TaskEntrySelector.vue'
import TaskEntryConfigDialog from './TaskEntryConfigDialog.vue'
import TaskWorkAdjustmentDialog from './TaskWorkAdjustmentDialog.vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import TaskWorkRuleFields from './TaskWorkRuleFields.vue'
import TaskWorkBudgetFields from './TaskWorkBudgetFields.vue'

const model = defineModel<TaskWorkEntryConfig[]>({ default: () => [] })
const totalMinutes = defineModel<number | null>('totalMinutes', { default: null })
const totalMode = defineModel<'AUTO' | 'MANUAL' | null>('totalMode', { default: null })
const props = defineProps<{
  nodes?: TaskNodeInput[]
  currentId?: string
  hierarchyRootId?: string
  inherited?: boolean
  isRoot?: boolean
  rootEntries?: TaskWorkEntryConfig[]
  readonly?: boolean
  workAdjustment?: boolean
  unified?: boolean
  legacyPolicy?: TaskDataPolicy | null
  bindingLocked?: boolean
  showWorkTotal?: boolean
  totalReadonly?: boolean
  budgetEntries?: TaskWorkEntryConfig[]
}>()
const workColumns = [
  { key: 'name', title: '办理表单', width: 180 },
  { key: 'rule', title: '工时计算规则', width: 460 },
  { key: 'quantity', title: '预计记录数', width: 140 },
  { key: 'subtotal', title: '预计工时', width: 120 }
]
const runtime = useNocodePlatform().runtime
const selectorOpen = ref(false),
  editing = ref<TaskWorkEntryConfig | null>(null),
  candidates = ref<TaskEntryCandidate[]>([])
const adjusting = ref<TaskWorkEntryConfig | null>(null)
const metadata = ref<
  Record<string, { applicationName: string; viewName: string; formName: string; unavailableReason?: string }>
>({})
let generation = 0
const signature = computed(() => model.value.map(e => taskEntryIdentity(e.binding)).join('|'))
watch(
  signature,
  async () => {
    const token = ++generation
    const ids = [...new Set(model.value.flatMap(e => (e.binding ? [e.binding.applicationId] : [])))]
    const results = await Promise.allSettled(ids.map(id => runtime.application(id)))
    if (token !== generation) return
    const next: typeof metadata.value = {}
    results.forEach((result, index) => {
      const app = result.status === 'fulfilled' ? result.value : null
      const resources = Array.isArray(app?.definition?.resources) ? app.definition.resources : null
      for (const entry of model.value.filter(e => e.binding?.applicationId === ids[index])) {
        const form = resources?.find(r => r.id === entry.binding?.formId && r.kind === 'FORM')
        const view = resources?.find(r => r.id === entry.binding?.viewId && r.kind === 'VIEW')
        next[entry.key] = {
          applicationName: app?.application?.name || '所属应用',
          viewName: view?.name || (entry.binding?.viewId ? '原视图' : '历史表单关联'),
          formName: form?.name || '原表单',
          unavailableReason: !resources
            ? '关联应用暂不可用，已保留原配置'
            : !form || (entry.binding?.viewId && !view)
              ? '原视图或表单暂不可用，已保留原配置'
              : undefined
        }
      }
    })
    metadata.value = next
  },
  { immediate: true }
)
onBeforeUnmount(() => generation++)
function confirmSelection(entries: TaskWorkEntryConfig[]) {
  if (props.readonly || props.workAdjustment) return
  const existing = new Set(model.value.map(entry => entry.key))
  const locked = model.value.find(entry => entry.key === '__business')
  if (props.bindingLocked && locked && !entries.some(entry => entry.key === '__business'))
    entries = [locked, ...entries]
  // 原高级共享和字段约束不能因重新确认多选而被清空；只为新增项设置默认值。
  model.value = entries.map(entry =>
    !existing.has(entry.key)
      ? {
          ...entry,
          dataScope: 'GROUP',
          workRule: entry.workRule || null,
          ...(props.unified ? { dataMode: 'ROOT_SHARED' as const } : {})
        }
      : entry
  )
  selectorOpen.value = false
}
function save(entry: TaskWorkEntryConfig) {
  if (props.readonly || props.workAdjustment) return
  model.value = model.value.map(item => (item.key === editing.value?.key ? entry : item))
  editing.value = null
}
function configure(entry: TaskWorkEntryConfig) {
  if (props.workAdjustment && entry.workRule) adjusting.value = entry
  else editing.value = entry
}
function saveAdjustment(adjustment: number) {
  if (!props.workAdjustment || !adjusting.value?.workRule) return
  // 发起入口只更新差额，不能顺带修改批准的表单、字段或数据范围。
  model.value = model.value.map(entry =>
    entry.key === adjusting.value?.key && entry.workRule
      ? { ...entry, workRule: { ...entry.workRule, adjustmentMinutes: adjustment || null } }
      : entry
  )
  adjusting.value = null
}
function remove(entry: TaskWorkEntryConfig) {
  if (props.readonly || props.workAdjustment || (props.bindingLocked && entry.key === '__business')) return
  Modal.confirm({
    title: `移除“${entry.name}”？`,
    content: '只移除当前编辑任务的关联配置，不删除已经存在的业务数据。',
    okText: '移除关联',
    okType: 'danger',
    cancelText: '保留',
    onOk: () => {
      model.value = model.value.filter(item => item.key !== entry.key)
    }
  })
}
function status(entry: TaskWorkEntryConfig) {
  return (
    candidates.value.find(
      candidate =>
        candidate.id === taskEntryIdentity(entry.binding) && !candidate.available && !candidate.pendingVerification
    )?.status || metadata.value[entry.key]?.unavailableReason
  )
}
function updateRule(entry: TaskWorkEntryConfig, rule: TaskWorkRule | null | undefined) {
  if (props.readonly || props.workAdjustment) return
  model.value = model.value.map(item => (item.key === entry.key ? { ...item, workRule: rule } : item))
}
function updateQuantity(entry: TaskWorkEntryConfig, value: number | string | null) {
  if (!entry.workRule || (props.readonly && !props.workAdjustment)) return
  const quantity = value == null || value === '' ? null : Number(value)
  if (taskWorkPlannedQuantityError({ ...entry.workRule, plannedQuantity: quantity })) return
  // 发起时只改本次预计量；不能借此入口改写模板规则、来源或字段权限。
  model.value = model.value.map(item =>
    item.key === entry.key && item.workRule
      ? { ...item, workRule: { ...item.workRule, plannedQuantity: quantity } }
      : item
  )
}
function quantityLabel(rule?: TaskWorkRule | null) {
  return rule?.mode === 'QUANTITY' ? '预计业务数量' : rule?.mode === 'CONDITION' ? '预计达标记录数' : '预计记录数'
}
function subtotal(entry: TaskWorkEntryConfig) {
  const rule = entry.workRule
  if (!rule || taskWorkRuleMinutes(rule) === 0 || rule.plannedQuantity == null) return '未设置'
  if (taskWorkRuleError(rule)) return '配置有误'
  const amount = rule.plannedQuantity
  const value = taskWorkRuleMinutes(rule) * amount
  if (value > 599999) return '超出范围'
  if (value === 0) return '0 分钟'
  return Number.isInteger(value) ? formatEffectiveWorkMinutes(value) : `${Number(value.toFixed(6))} 分钟`
}
</script>
<template>
  <div class="business-items">
    <div class="business-items__heading">
      <div class="business-items__label">
        <strong>业务办理项</strong>
        <span v-if="model.length" class="business-items__count">{{ model.length }} 项</span>
        <a-tooltip
          :trigger="['hover', 'focus', 'click']"
          title="选择需要办理的业务视图，可按需设置标准工时；不设置工时不影响任务办理。工时按有效办理结果计算，不按实际用时累计。"
        >
          <button type="button" class="business-items__help" aria-label="业务办理项说明">
            <QuestionCircleOutlined />
          </button>
        </a-tooltip>
      </div>
      <a-button v-if="!readonly && !workAdjustment" size="small" @click="selectorOpen = true">
        <PlusOutlined />
        选择业务视图
      </a-button>
    </div>
    <div v-if="!model.length" class="business-items__empty">
      <AppstoreOutlined />
      <strong>{{ inherited ? '沿用上级业务关联' : '还没有业务办理项' }}</strong>
      <p>
        {{
          inherited
            ? '上级已配置的视图、表单和授权保持不变；可按需添加本节点的办理项。'
            : '选择房间登记、采购订单等业务视图，员工即可在任务中逐项办理。无需业务数据的任务可留空。'
        }}
      </p>
      <a-button v-if="!readonly && !workAdjustment" type="dashed" @click="selectorOpen = true">选择业务视图</a-button>
    </div>
    <div v-else class="business-items__grid">
      <article v-for="entry in model" :key="entry.key" class="business-item" @click="configure(entry)">
        <div class="business-item__title">
          <AppstoreOutlined />
          <strong>
            <button
              type="button"
              class="business-item__configure"
              :title="entry.name"
              :aria-label="`${workAdjustment && entry.workRule ? '调整本次工时' : readonly ? '查看配置' : '配置'}：${entry.name || '未命名办理项'}`"
              aria-haspopup="dialog"
              @click.stop="configure(entry)"
            >
              {{ entry.name || '未命名办理项' }}
            </button>
          </strong>
          <a-tag v-if="entry.required" color="blue">必办</a-tag>
          <a-tooltip
            v-if="!readonly && !workAdjustment"
            :title="
              bindingLocked && entry.key === '__business'
                ? '此关联由任务来源固定，不能移除'
                : '移除关联，不删除业务数据'
            "
          >
            <span class="business-item__remove" @click.stop>
              <a-button
                type="text"
                size="small"
                :disabled="bindingLocked && entry.key === '__business'"
                :aria-label="`移除关联：${entry.name}`"
                @click="remove(entry)"
              >
                <CloseOutlined />
              </a-button>
            </span>
          </a-tooltip>
        </div>
        <dl class="business-item__source">
          <dt>业务视图</dt>
          <dd :title="metadata[entry.key]?.viewName">
            {{ metadata[entry.key]?.viewName || (entry.binding?.viewId ? '加载中…' : '历史表单关联') }}
          </dd>
          <dt>填写表单</dt>
          <dd :title="metadata[entry.key]?.formName">{{ metadata[entry.key]?.formName || '加载中…' }}</dd>
        </dl>
        <p v-if="status(entry)" class="business-item__warning">{{ status(entry) }}</p>
        <div class="business-item__footer">
          <div class="business-item__scope">
            <span>{{ taskEntryScopeLabel(taskEntryScope(entry, legacyPolicy)) }}</span>
            <span v-if="!unified && entry.dataMode !== 'INDEPENDENT'" class="business-item__relation">
              受来源范围限制
            </span>
          </div>
        </div>
      </article>
    </div>
    <OsTablePage
      v-if="model.length"
      class="business-items__work-table"
      title="工时安排"
      :columns="workColumns"
      :data-source="model"
      row-key="key"
      :show-index="false"
      :show-column-settings="false"
      :resizable="false"
      :pagination="false"
      :scroll="{ x: 900 }"
      fixed-layout
      size="small"
    >
      <template #bodyCell="{ column, record: entry }">
        <template v-if="column.key === 'name'">
          <strong>{{ entry.name || '未命名办理项' }}</strong>
          <p class="business-items__cell-hint">{{ metadata[entry.key]?.formName || '原表单' }}</p>
        </template>
        <template v-else-if="column.key === 'rule'">
          <TaskWorkRuleFields
            v-if="!workAdjustment"
            :model-value="entry.workRule"
            :binding="entry.binding"
            compact
            :readonly="readonly"
            :label="`${entry.name}标准工时`"
            @update:model-value="updateRule(entry, $event)"
          />
          <template v-else-if="entry.workRule">
            <strong>{{ taskWorkRuleSummary(entry.workRule) }}</strong>
            <a-button type="link" size="small" :aria-label="`调整本次工时：${entry.name}`" @click="adjusting = entry">
              加减本次工时
            </a-button>
            <p v-if="entry.workRule.adjustmentMinutes" class="business-item__adjustment">
              {{ taskWorkAdjustmentSummary(entry.workRule) }}
            </p>
          </template>
          <span v-else class="business-items__cell-hint">未设置标准工时</span>
        </template>
        <template v-else-if="column.key === 'quantity'">
          <template v-if="entry.workRule">
            <a-input-number
              class="business-items__quantity"
              :value="entry.workRule.plannedQuantity"
              :disabled="readonly && !workAdjustment"
              :min="0"
              :max="999999"
              :precision="entry.workRule.mode === 'QUANTITY' ? 6 : 0"
              :formatter="formatTaskWorkQuantity"
              :aria-label="`${entry.name}${quantityLabel(entry.workRule)}`"
              :title="quantityLabel(entry.workRule)"
              placeholder="选填"
              @update:value="updateQuantity(entry, $event)"
            />
          </template>
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'subtotal'">
          <strong>{{ subtotal(entry) }}</strong>
        </template>
      </template>
    </OsTablePage>
    <TaskWorkBudgetFields
      v-if="showWorkTotal"
      v-model:minutes="totalMinutes"
      v-model:mode="totalMode"
      :entries="budgetEntries ?? model"
      :readonly="totalReadonly ?? readonly"
    />
    <TaskEntrySelector
      :open="selectorOpen && !readonly && !workAdjustment"
      :entries="model"
      :is-root="isRoot"
      @cancel="selectorOpen = false"
      @confirm="confirmSelection"
      @catalog="candidates = $event"
    />
    <TaskEntryConfigDialog
      :entry="editing"
      :source-info="editing ? metadata[editing.key] : undefined"
      :readonly="readonly || workAdjustment"
      :unified="unified"
      :legacy-policy="legacyPolicy"
      :nodes="nodes"
      :current-id="currentId"
      :root-entries="rootEntries"
      @cancel="editing = null"
      @save="save"
    />
    <TaskWorkAdjustmentDialog
      :entry="workAdjustment ? adjusting : null"
      @cancel="adjusting = null"
      @save="saveAdjustment"
    />
  </div>
</template>
<style scoped>
.business-items {
  display: grid;
  gap: var(--spacing-lg);
  min-width: 0;
}
.business-items__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
}
.business-items__label {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.business-items__count,
.business-items__adjustment-hint,
.business-item__adjustment,
.business-items__help {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.business-items__adjustment-hint {
  margin: 0;
}
.business-items__help {
  display: inline-flex;
  padding: var(--spacing-xs);
  border: 0;
  background: transparent;
  cursor: help;
}
.business-items__help:focus-visible {
  outline: 2px solid var(--brand);
  border-radius: var(--border-radius, 6px);
}
.business-items__empty p {
  margin: var(--spacing-xs) 0 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.business-items__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 300px), 1fr));
  gap: var(--spacing-lg);
}
.business-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1px solid var(--color-border-secondary, #e5e7eb);
  border-radius: var(--border-radius-lg, 8px);
  background: var(--color-bg-container, #fff);
  min-width: 0;
  cursor: pointer;
}
.business-item:hover {
  border-color: var(--brand);
}
.business-item:has(.business-item__configure:focus-visible) {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.business-item__title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.business-item__title > .anticon {
  color: var(--brand);
}
.business-item__title strong {
  flex: 1;
  min-width: 0;
}
.business-item__configure {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.business-item__configure:focus-visible {
  outline: none;
}
.business-item__title .ant-tag {
  margin: 0;
}
.business-item p {
  margin: 0;
  font-size: var(--table-font-sm);
  overflow-wrap: anywhere;
}
.business-item__source {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--spacing-sm);
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.business-item__source dd {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.business-items__cell-hint {
  margin: var(--spacing-xs) 0 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.business-items__work-table {
  min-width: 0;
}
.business-items__work-table :deep(.os-table-page__table) {
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.business-items__work-table :deep(.ant-card-head) {
  min-height: 0;
  padding: 0 0 var(--spacing-md);
  border-bottom: 0;
  font-size: var(--font-size-base, 14px);
}
.business-items__work-table :deep(.ant-card-body) {
  padding: 0;
}
.business-items__work-table :deep(.ant-table-tbody > tr > td) {
  padding-block: var(--spacing-md);
}
.business-items__work-table .business-items__quantity {
  width: 112px;
  max-width: 100%;
}
.business-item__warning {
  color: var(--color-warning, #ad6800);
}
.business-item__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-top: auto;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.business-item__scope {
  display: grid;
  gap: var(--spacing-xs);
}
.business-item__relation {
  font-size: var(--table-font-sm);
}
.business-item__remove {
  flex-shrink: 0;
}
.business-item__remove :deep(.ant-btn:not(:disabled):hover) {
  color: var(--color-error, #ff4d4f);
  background: var(--color-error-bg, #fff2f0);
}
.business-items__empty {
  display: grid;
  justify-items: center;
  gap: var(--spacing-sm);
  text-align: center;
  padding: var(--spacing-xl) var(--spacing-lg);
  border: 1px dashed var(--color-border, #d9d9d9);
  border-radius: var(--border-radius-lg, 8px);
}
.business-items__empty > .anticon {
  font-size: 24px;
  color: var(--brand);
}
.business-items__empty p {
  max-width: 540px;
  margin-bottom: var(--spacing-sm);
}
</style>
