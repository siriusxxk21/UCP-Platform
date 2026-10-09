<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import type { TaskSchedule } from '@/types/nocode/task-center'
import { taskDate } from '@/nocode/task-center'
const schedule = defineModel<TaskSchedule>({ required: true })
const props = withDefaults(
  defineProps<{
    readonly?: boolean
    plannedStart?: string | null
    templateEditing?: boolean
    compact?: boolean
    hasPredecessors?: boolean
    hasInheritedPredecessors?: boolean
    hasChildren?: boolean
    isRoot?: boolean
  }>(),
  // 拆分入口未加载祖先关系；不能把未提供上下文当作明确没有继承前序。
  { hasInheritedPredecessors: undefined }
)
// 旧草稿可保留精确时间；仅回显日期，用户重新选择后才写入日期值。
const fixedStartDate = computed({
  get: () => (schedule.value.fixedStart ? dayjs(schedule.value.fixedStart).format('YYYY-MM-DD') : null),
  set: (value: string | null) => (schedule.value.fixedStart = value)
})
const fixedEndDate = computed({
  get: () => (schedule.value.fixedEnd ? dayjs(schedule.value.fixedEnd).format('YYYY-MM-DD') : null),
  set: (value: string | null) => (schedule.value.fixedEnd = value)
})
const arrangement = computed(() =>
  ['AUTO', 'PLAN_START', 'PREDECESSOR', 'T0'].includes(schedule.value.mode) ? 'RELATIVE' : schedule.value.mode
)
const autoRollup = computed(() => schedule.value.mode === 'AUTO' && props.hasChildren)
const autoBasisUnknown = computed(
  () => !props.isRoot && !props.hasPredecessors && props.hasInheritedPredecessors === undefined
)
const autoBasis = computed(() => {
  if (props.hasChildren) return '开始取下级最早日期，完成取下级最晚日期'
  if (autoBasisUnknown.value) return '有前序时接续完成日期，无前序时跟随整体计划开始。'
  if (props.hasPredecessors || props.hasInheritedPredecessors)
    return `${props.hasInheritedPredecessors ? '含上级的前序任务，' : ''}按前序任务最晚完成日期接续；已完成取实际日期，未完成取预计日期。`
  return props.isRoot ? '按整体计划开始日期安排。' : '无前序任务时跟随整体计划开始，并行任务同日开始。'
})
// 意图只控制展示；已有起点规则始终原样回显，不因打开面板转换排期语义。
const intent = computed(() =>
  schedule.value.mode === 'AUTO' || schedule.value.mode === 'UNSCHEDULED' ? schedule.value.mode : 'CUSTOM'
)
const scheduleOptions = computed(() => [
  { value: 'AUTO', label: props.hasChildren ? '按下级汇总' : '随任务顺序', description: autoBasis.value },
  {
    value: 'CUSTOM',
    label: props.isRoot && props.hasChildren ? '单独安排总任务' : '单独安排',
    description: '设置自己的开始依据和工期'
  },
  { value: 'UNSCHEDULED', label: '暂不安排', description: '不设置计划日期，可稍后再安排' }
])
const customOptions = computed(() => [
  { value: 'PLAN_START', label: '按计划开始日期' },
  ...(props.hasPredecessors !== false || schedule.value.mode === 'PREDECESSOR'
    ? [{ value: 'PREDECESSOR', label: '接在前序任务后', disabled: props.hasPredecessors === false }]
    : []),
  ...(!props.templateEditing || schedule.value.mode === 'FIXED'
    ? [{ value: 'FIXED', label: '指定开始与完成日期', disabled: props.templateEditing }]
    : []),
  // 创建时间只用于已有配置的兼容回显，新安排不再引入另一套起点。
  ...(schedule.value.mode === 'T0' ? [{ value: 'T0', label: '任务创建时开始（旧规则）' }] : [])
])
const customHint = computed(() => {
  if (schedule.value.mode === 'PLAN_START')
    return '以发起时填写的计划开始日期为准，不是创建时间或实际开工时间；执行仍需满足任务顺序。'
  if (schedule.value.mode === 'PREDECESSOR')
    return props.hasPredecessors === false
      ? '当前仍保留原规则；请先设置前序任务，或选择其他排期方式。'
      : '以前序任务最晚完成日期接续；未完成取预计日期，已完成取实际日期。'
  if (schedule.value.mode === 'T0') return '旧配置按任务创建时间计算，现有日期不变；可主动改为按计划开始日期。'
  return ''
})
function changeIntent(value: unknown) {
  if (props.readonly) return
  if (value === 'CUSTOM') {
    if (intent.value !== 'CUSTOM') change('PLAN_START')
  } else if (value === 'AUTO' || value === 'UNSCHEDULED') change(value)
}
function change(value: unknown) {
  if (props.readonly || (value === 'PREDECESSOR' && props.hasPredecessors === false)) return
  if (!['AUTO', 'FIXED', 'UNSCHEDULED', 'PLAN_START', 'PREDECESSOR', 'T0'].includes(String(value))) return
  if (value === 'T0' && schedule.value.mode !== 'T0') return
  if (props.templateEditing && value === 'FIXED') return
  if (
    !props.templateEditing &&
    schedule.value.mode === 'FIXED' &&
    !schedule.value.fixedEnd &&
    schedule.value.fixedStart &&
    schedule.value.durationDays
  )
    schedule.value.fixedEnd = dayjs(schedule.value.fixedStart)
      .add(schedule.value.durationDays, 'day')
      .format('YYYY-MM-DDTHH:mm:ss')
  schedule.value.mode = value as TaskSchedule['mode']
  if (value === 'FIXED') schedule.value.durationDays = 0
}
const startArrangement = computed(() => (schedule.value.offsetDays === 0 ? 'SAME_DAY' : 'DELAY'))
function changeStart(value: string) {
  if (props.readonly) return
  if (value === 'SAME_DAY') schedule.value.offsetDays = 0
  else if (schedule.value.offsetDays === 0) schedule.value.offsetDays = 1
}
// AUTO 由服务端统一预览，不能在单节点控件里复制含祖先前置与父节点汇总的排期计算。
// 仅对已有整体起点提供日期预览；前序依赖与旧 T0 的日期以服务端计算结果为准。
const plannedDates = computed(() => {
  if (props.templateEditing) return null
  const rule = schedule.value
  if (rule.mode === 'FIXED') {
    const end =
      rule.fixedEnd ||
      (rule.fixedStart && rule.durationDays ? dayjs(rule.fixedStart).add(rule.durationDays, 'day').format() : null)
    return { start: taskDate(rule.fixedStart), end: taskDate(end) }
  }
  if (rule.mode !== 'PLAN_START' || !props.plannedStart || !dayjs(props.plannedStart).isValid()) return null
  if (!Number.isFinite(rule.offsetDays) || !Number.isFinite(rule.durationDays)) return null
  const start = dayjs(props.plannedStart).add(rule.offsetDays, 'day')
  return { start: taskDate(start.format()), end: taskDate(start.add(rule.durationDays, 'day').format()) }
})
const ruleSummary = computed(() => {
  const rule = schedule.value
  if (rule.mode === 'UNSCHEDULED') return '暂不设置计划日期。'
  if (autoRollup.value) return '自动汇总下级任务；开始取最早日期，完成取最晚日期，不单独设置工期。'
  if (rule.mode === 'FIXED') {
    if (rule.fixedStart && !rule.fixedEnd && rule.durationDays)
      return `从 ${taskDate(rule.fixedStart)} 开始，按原工期 ${rule.durationDays} 天安排。`
    return `指定开始 ${taskDate(rule.fixedStart)}，完成 ${taskDate(rule.fixedEnd)}。`
  }
  const basis =
    rule.mode === 'T0'
      ? '任务创建时'
      : rule.mode === 'PREDECESSOR'
        ? '前序任务最晚完成时'
        : rule.mode === 'AUTO' && (props.hasPredecessors || props.hasInheritedPredecessors)
          ? '前序任务最晚完成时'
          : '整项任务计划开始日'
  const offset = Number.isFinite(rule.offsetDays)
    ? rule.offsetDays === 0
      ? '当天开始'
      : rule.offsetDays > 0
        ? `后 ${rule.offsetDays} 天开始`
        : `提前 ${Math.abs(rule.offsetDays)} 天开始`
    : '开始（间隔待填写）'
  const duration = Number.isFinite(rule.durationDays) ? `计划 ${rule.durationDays} 天（自然日）` : '工期待填写'
  if (rule.mode === 'AUTO' && autoBasisUnknown.value) {
    const gap = !Number.isFinite(rule.offsetDays)
      ? '（间隔待填写）'
      : rule.offsetDays > 0
        ? `，再间隔 ${rule.offsetDays} 天`
        : rule.offsetDays < 0
          ? `，提前 ${Math.abs(rule.offsetDays)} 天`
          : ''
    return `按任务顺序确定开始日${gap}，${duration}。`
  }
  const ownDuration =
    props.hasChildren && rule.mode !== 'AUTO'
      ? `使用${props.isRoot ? '总任务' : '当前任务'}自己的工期，不随下级汇总。`
      : ''
  return `从${basis}${offset}，${duration}。${ownDuration}`
})
</script>
<template>
  <div
    :class="compact ? 'task-schedule-fields--compact' : 'task-schedule-fields task-form-grid'"
    role="group"
    aria-label="预计时间安排"
  >
    <a-select
      v-if="compact"
      :value="intent"
      :options="scheduleOptions"
      :disabled="readonly"
      aria-label="时间规则"
      @change="changeIntent"
    />
    <a-form-item v-else label="排期方式" class="task-form-grid__full">
      <a-radio-group
        class="task-schedule-fields__modes"
        :value="intent"
        :disabled="readonly"
        @change="changeIntent($event.target.value)"
      >
        <a-radio v-for="option in scheduleOptions" :key="option.value" :value="option.value">
          <span class="task-schedule-fields__option">
            <span class="task-schedule-fields__option-title">{{ option.label }}</span>
            <span class="task-schedule-fields__option-description">{{ option.description }}</span>
          </span>
        </a-radio>
      </a-radio-group>
    </a-form-item>
    <a-form-item
      v-if="intent === 'CUSTOM' && customOptions.length > 1"
      label="从什么时候开始"
      class="task-form-grid__full"
    >
      <a-select
        :value="schedule.mode"
        :options="customOptions"
        :disabled="readonly"
        aria-label="从什么时候开始"
        @change="change"
      />
      <p v-if="customHint" class="task-list__hint task-schedule-fields__basis">{{ customHint }}</p>
    </a-form-item>
    <!-- 总任务未使用相对时间时，仍可为子任务设置共同起点。 -->
    <div v-if="!compact && !templateEditing && $slots['planned-start']" class="task-form-grid__full">
      <slot name="planned-start" />
    </div>
    <p
      v-if="compact && schedule.mode === 'AUTO'"
      class="task-list__hint task-form-grid__full task-schedule-fields__basis"
    >
      {{ autoBasis }}
    </p>
    <p v-if="templateEditing && arrangement === 'FIXED'" class="task-list__hint task-form-grid__full">
      原预计开始：{{ taskDate(schedule.fixedStart) }}；原预计结束：{{ taskDate(schedule.fixedEnd) }}
    </p>
    <template v-else-if="compact && arrangement === 'FIXED'">
      <a-date-picker
        v-model:value="fixedStartDate"
        :disabled="readonly"
        format="YYYY-MM-DD"
        value-format="YYYY-MM-DD"
        placeholder="预计开始（可选）"
      />
      <a-date-picker
        v-model:value="fixedEndDate"
        :disabled="readonly"
        format="YYYY-MM-DD"
        value-format="YYYY-MM-DD"
        placeholder="预计结束（可选）"
      />
    </template>
    <template v-else-if="arrangement === 'FIXED'">
      <a-form-item label="计划开始日期">
        <a-date-picker
          v-model:value="fixedStartDate"
          :disabled="readonly"
          format="YYYY-MM-DD"
          value-format="YYYY-MM-DD"
          placeholder="选择开始日期（可选）"
        />
      </a-form-item>
      <a-form-item label="计划完成日期">
        <a-date-picker
          v-model:value="fixedEndDate"
          :disabled="readonly"
          format="YYYY-MM-DD"
          value-format="YYYY-MM-DD"
          placeholder="选择完成日期（可选）"
        />
      </a-form-item>
    </template>
    <div v-if="compact && arrangement === 'RELATIVE' && !autoRollup" class="task-schedule-fields__numbers">
      <label>
        {{ schedule.mode === 'AUTO' ? '间隔' : '延后' }}
        <a-input-number
          v-model:value="schedule.offsetDays"
          aria-label="延后天数"
          :disabled="readonly"
          :min="0"
          :precision="0"
        />
        天
      </label>
      <label>
        计划工期
        <a-input-number
          v-model:value="schedule.durationDays"
          aria-label="预计工期天数"
          :disabled="readonly"
          :min="0"
          :precision="0"
        />
        天
      </label>
    </div>
    <template v-else-if="arrangement === 'RELATIVE' && !autoRollup">
      <a-form-item v-if="schedule.mode === 'AUTO'" label="计划工期（自然日）">
        <a-input-number
          v-model:value="schedule.durationDays"
          aria-label="预计工期天数"
          :disabled="readonly"
          :min="0"
          :precision="0"
          addon-after="天"
        />
      </a-form-item>
      <a-form-item
        :label="schedule.mode === 'AUTO' ? '开始间隔（可选）' : '开始安排（自然日）'"
        class="task-form-grid__full"
      >
        <div class="task-schedule-fields__start">
          <a-radio-group :value="startArrangement" :disabled="readonly" @change="changeStart($event.target.value)">
            <a-radio value="SAME_DAY">{{ schedule.mode === 'AUTO' ? '无间隔' : '当天开始' }}</a-radio>
            <a-radio value="DELAY">{{ schedule.mode === 'AUTO' ? '间隔后开始' : '延后开始' }}</a-radio>
          </a-radio-group>
          <a-input-number
            v-if="startArrangement === 'DELAY'"
            v-model:value="schedule.offsetDays"
            aria-label="延后天数"
            :disabled="readonly"
            :min="1"
            :precision="0"
            placeholder="天数"
            addon-after="天"
          />
        </div>
      </a-form-item>
      <a-form-item v-if="schedule.mode !== 'AUTO'" label="计划工期（自然日）">
        <a-input-number
          v-model:value="schedule.durationDays"
          aria-label="预计工期天数"
          :disabled="readonly"
          :min="0"
          :precision="0"
          addon-after="天"
        />
      </a-form-item>
    </template>
    <dl
      v-if="plannedDates && !compact"
      class="task-schedule-fields__dates task-form-grid__full"
      aria-label="预计日期"
      role="status"
    >
      <div>
        <dt>预计开始</dt>
        <dd>{{ plannedDates.start }}</dd>
      </div>
      <div>
        <dt>预计完成</dt>
        <dd>{{ plannedDates.end }}</dd>
      </div>
    </dl>
    <p class="task-schedule-fields__result task-form-grid__full" aria-label="本次安排">本次安排：{{ ruleSummary }}</p>
  </div>
</template>
<style scoped>
.task-schedule-fields__modes {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}
.task-schedule-fields__basis {
  margin: 0;
}
.task-schedule-fields__option {
  display: grid;
  gap: var(--spacing-xs);
}
.task-schedule-fields__option-title {
  font-weight: 500;
}
.task-schedule-fields__option-description {
  font-size: var(--font-size-sm, 12px);
  color: var(--text-secondary);
}
.task-schedule-fields__modes :deep(.ant-radio-wrapper) {
  align-items: flex-start;
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.task-schedule-fields__modes :deep(.ant-radio-wrapper-checked) {
  border-color: var(--brand);
  background: var(--brand-light);
}
.task-schedule-fields__start {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.task-schedule-fields__start :deep(.ant-input-number-group-wrapper) {
  width: 132px;
}
.task-schedule-fields__dates {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--spacing-lg);
  padding: var(--spacing-md);
  margin: 0;
  border-radius: var(--radius-sm);
  background: var(--neutral-bg);
}
.task-schedule-fields__dates dt {
  color: var(--text-secondary);
  margin-bottom: var(--spacing-xs);
}
.task-schedule-fields__dates dd {
  margin: 0;
}
.task-schedule-fields--compact {
  display: grid;
  gap: var(--spacing-xs);
  min-width: 0;
}
.task-schedule-fields__numbers {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  font-size: var(--font-size-sm, 12px);
}
.task-schedule-fields__numbers label {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}
.task-schedule-fields__numbers :deep(.ant-input-number) {
  width: 60px;
}
.task-schedule-fields__result {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--neutral-bg);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--font-size-sm, 12px);
  line-height: 1.6;
}
.task-schedule-fields--compact .task-schedule-fields__result {
  padding: var(--spacing-xs) var(--spacing-sm);
}
</style>
