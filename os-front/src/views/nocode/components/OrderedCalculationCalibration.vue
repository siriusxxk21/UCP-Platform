<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { storedOrderedCalculation } from '@/nocode/calculation-presentation'
import { orderedStateLabel } from '@/nocode/ordered-calculation'
import { errorMessage } from '@/nocode/data-center'
import type { ObjectDataModel } from '@/types/nocode/object-data'
import type {
  OrderedCalculationState,
  OrderedCalibrationCommand,
  OrderedCalibrationPreview,
  OrderedCalibrationResult
} from '@/types/nocode/ordered-calculation'

const props = defineProps<{ objectId: string; model: ObjectDataModel }>()
const emit = defineEmits<{ cancel: []; changed: [] }>()
const api = useNocodePlatform().objectData
const model = ref(props.model)
const states = ref<OrderedCalculationState[]>(props.model.model.orderedStates || [])
const fieldIds = ref<string[]>([])
const preview = ref<OrderedCalibrationPreview>()
const command = ref<OrderedCalibrationCommand>()
const checking = ref(false),
  advancing = ref(false),
  failure = ref(''),
  acknowledged = ref(false)
const keepRunning = ref(false)
const pauseRequested = ref(false)
let mounted = true
let generation = 0
const choices = computed(() =>
  model.value.model.object.fields
    .filter(field => storedOrderedCalculation(model.value.model.object.fieldOptions[field.id || field.key]))
    .map(field => ({ value: field.id || field.key, label: field.name }))
)
const fieldName = (id: string) => choices.value.find(field => field.value === id)?.label || id
const pending = computed(() => states.value.filter(state => state.state !== 'READY'))
const runs = computed(() =>
  [
    ...new Set(
      states.value.map(state => state.cursor?.requestId).filter((id): id is string => typeof id === 'string' && !!id)
    )
  ]
    .map(id => ({ id, states: states.value.filter(state => state.cursor?.requestId === id) }))
    .filter(run => run.states.some(state => state.state !== 'READY'))
)
const canStart = computed(
  () =>
    !!preview.value?.fields.length && acknowledged.value && !checking.value && !advancing.value && !runs.value.length
)

async function refresh() {
  preview.value = undefined
  acknowledged.value = false
  command.value = undefined
  const current = ++generation
  checking.value = true
  failure.value = ''
  try {
    const [fresh, status] = await Promise.all([api.model(props.objectId), api.calculationStatus(props.objectId)])
    if (!mounted || current !== generation) return
    model.value = fresh
    states.value = status
    if (!fieldIds.value.length) fieldIds.value = choices.value.map(field => field.value)
    else fieldIds.value = fieldIds.value.filter(id => choices.value.some(field => field.value === id))
  } catch (error) {
    if (mounted && current === generation) failure.value = errorMessage(error)
  } finally {
    if (mounted && current === generation) checking.value = false
  }
}
function clearPreview() {
  checking.value = false
  preview.value = undefined
  acknowledged.value = false
  command.value = undefined
  generation++
}
async function inspect() {
  if (advancing.value || !fieldIds.value.length) return
  clearPreview()
  const current = ++generation
  checking.value = true
  failure.value = ''
  const selected = [...fieldIds.value]
  try {
    const fresh = await api.model(props.objectId)
    if (!mounted || current !== generation) return
    model.value = fresh
    const result = await api.calculationPreview({
      objectId: props.objectId,
      versionNo: fresh.versionNo,
      checksum: fresh.checksum,
      fieldIds: selected
    })
    if (mounted && current === generation) preview.value = result
  } catch (error) {
    if (mounted && current === generation) failure.value = errorMessage(error)
  } finally {
    if (mounted && current === generation) checking.value = false
  }
}
function acceptResult(result: OrderedCalibrationResult) {
  if (!mounted) return
  const updates = new Map(result.states.map(state => [state.fieldId, state]))
  states.value = [...states.value.filter(state => !updates.has(state.fieldId)), ...result.states]
  emit('changed')
  if (result.complete) {
    preview.value = undefined
    acknowledged.value = false
    command.value = undefined
  }
}
async function execute(first: 'start' | 'resume' | 'retry' | 'pause') {
  if (advancing.value || !command.value) return
  advancing.value = true
  keepRunning.value = true
  pauseRequested.value = first === 'pause'
  failure.value = ''
  let action = first
  try {
    while (keepRunning.value && command.value) {
      if (!mounted) break
      const current = { ...command.value }
      const result = await (
        action === 'start'
          ? api.calibrate
          : action === 'retry'
            ? api.retryCalculation
            : action === 'pause'
              ? api.pauseCalculation
              : api.resumeCalculation
      )(current)
      acceptResult(result)
      // 显式暂停必须持久化；等待当前批提交后才发出，即使其间关闭了弹窗。
      if (pauseRequested.value && action !== 'pause' && !result.complete) {
        acceptResult(await api.pauseCalculation(current))
        break
      }
      if (!mounted || result.complete || action === 'pause') break
      if (result.states.some(state => state.state === 'FAILED')) break
      action = 'resume'
    }
  } catch (error) {
    if (mounted) failure.value = `${errorMessage(error)}；进度保存在服务端，请刷新状态或按原批次重试。`
  } finally {
    if (mounted) {
      advancing.value = false
      keepRunning.value = false
      pauseRequested.value = false
    }
  }
}
function start() {
  if (!canStart.value || !preview.value) return
  const checked = preview.value
  // 同一批次在网络失败后保持操作标识；不把重试变成新的全量校准。
  command.value ??= {
    objectId: props.objectId,
    versionNo: checked.versionNo,
    checksum: checked.checksum,
    fieldIds: checked.fields.map(field => field.fieldId),
    signatures: Object.fromEntries(checked.fields.map(field => [field.fieldId, field.signature])),
    requestId: uuidv4(),
    maxGroups: 1
  }
  void execute('start')
}
function restoreCommand(run: { id: string; states: OrderedCalculationState[] }): boolean {
  if (checking.value || advancing.value) return false
  const cursor = run.states[0]?.cursor
  if (typeof cursor?.versionNo !== 'number' || typeof cursor?.checksum !== 'string') {
    failure.value = '此批次缺少固定版本信息，请刷新状态后重试，不能按最新定义重新解释旧批次。'
    return false
  }
  command.value = {
    objectId: props.objectId,
    versionNo: cursor.versionNo,
    checksum: cursor.checksum,
    fieldIds: run.states.map(state => state.fieldId),
    signatures: Object.fromEntries(run.states.map(state => [state.fieldId, state.signature])),
    requestId: run.id,
    maxGroups: 1
  }
  return true
}
function resume(run: { id: string; states: OrderedCalculationState[] }) {
  if (!restoreCommand(run)) return
  void execute(run.states.some(state => state.state === 'FAILED') ? 'retry' : 'resume')
}
function pauseRun(run: { id: string; states: OrderedCalculationState[] }) {
  if (restoreCommand(run)) void execute('pause')
}
function requestPause() {
  if (!advancing.value || !command.value) return
  pauseRequested.value = true
  keepRunning.value = false
}
function close() {
  keepRunning.value = false
  emit('cancel')
}
onMounted(() => void refresh())
onBeforeUnmount(() => {
  mounted = false
  keepRunning.value = false
  generation++
})
</script>
<template>
  <OsModalForm :open="true" title="校准有序计算数据" :width="900" :allow-switch-display="false" @cancel="close">
    <template #formItems>
      <a-alert v-if="failure" type="error" show-icon :message="failure" />
      <a-alert
        v-if="pending.length"
        type="warning"
        show-icon
        message="有序计算尚未全部就绪"
        description="校准完成前，相关写入以及这些结果的筛选、排序和统计保持受限。关闭页面会保留进度，不会自动解除维护。"
      />
      <section v-for="run in runs" :key="run.id" class="calibration-run">
        <strong>待继续的校准批次</strong>
        <p>{{ run.states.map(state => fieldName(state.fieldId)).join('、') }}</p>
        <a-space>
          <a-button :disabled="checking || advancing" @click="resume(run)">
            {{ run.states.some(state => state.state === 'FAILED') ? '重试或继续此批次' : '继续此批次' }}
          </a-button>
          <a-button
            v-if="run.states.some(state => state.state === 'BACKFILLING')"
            :disabled="checking || advancing"
            @click="pauseRun(run)"
          >
            暂停此批次
          </a-button>
        </a-space>
      </section>
      <a-form-item label="选择有序计算字段" :label-col="{ span: 24 }" :wrapper-col="{ span: 24 }">
        <a-select
          v-model:value="fieldIds"
          mode="multiple"
          :options="choices"
          :disabled="checking || advancing || !!runs.length"
          aria-label="选择校准字段"
          @change="clearPreview"
        />
        <p class="calibration-hint">
          仅校准保存时落库的增减累计、逐笔累计和相邻取值。普通保存快照及业务确认值不会被覆盖；当前列表筛选与分页不缩小校准范围。
        </p>
      </a-form-item>
      <a-button :disabled="!fieldIds.length || advancing || !!runs.length" :loading="checking" @click="inspect">
        预览校准范围
      </a-button>
      <a-table
        v-if="preview"
        class="calibration-table"
        size="small"
        :pagination="false"
        :scroll="{ x: 900 }"
        :data-source="preview.fields"
        row-key="fieldId"
        :columns="[
          { title: '字段', key: 'field' },
          { title: '当前状态', key: 'state' },
          { title: '分组数', dataIndex: 'groups' },
          { title: '记录数', dataIndex: 'rows' },
          { title: '需更新行数', dataIndex: 'changedRows' },
          { title: '空值待填', dataIndex: 'fillRows' },
          { title: '非空差异', dataIndex: 'incorrectRows' },
          { title: '合法空结果', dataIndex: 'validNullRows' }
        ]"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'field'">{{ fieldName(record.fieldId) }}</template>
          <template v-else-if="column.key === 'state'">{{ orderedStateLabel(record.state) }}</template>
        </template>
      </a-table>
      <template v-if="preview">
        <p class="calibration-hint">
          空值数量不等于待修复数量：合法空结果会保留，已有非空错误也会重新计算。每组独立提交，只写实际变化的结果。
        </p>
        <a-checkbox v-model:checked="acknowledged" :disabled="advancing">
          已确认：执行期间暂停本对象相关写入，完成全部校准后才恢复使用落库结果。
        </a-checkbox>
      </template>
      <a-table
        v-if="states.length"
        class="calibration-table"
        size="small"
        :pagination="false"
        :data-source="states"
        row-key="fieldId"
        :columns="[
          { title: '字段', key: 'field' },
          { title: '状态', key: 'state' },
          { title: '已完成组数', dataIndex: 'completedGroups' },
          { title: '已更新行数', dataIndex: 'updatedRows' },
          { title: '最近错误', dataIndex: 'error' }
        ]"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'field'">{{ fieldName(record.fieldId) }}</template>
          <template v-else-if="column.key === 'state'">{{ orderedStateLabel(record.state) }}</template>
        </template>
      </a-table>
      <p class="calibration-hint">
        页面打开时每次处理一个分组，分组较大时可能需要等待数分钟。关闭后不再发起下一批；正在处理的一批完成后进度仍会保留，下次可从此处继续。连接超时后请刷新状态，继续原批次。
        显式暂停会在本批完成后保存暂停状态，可继续原批次，也可由管理员切回读取时计算（LIVE）并发布；暂停本身不会恢复写入。
      </p>
    </template>
    <template #footer>
      <a-space>
        <a-button @click="close">{{ advancing || runs.length ? '关闭并保留进度' : '关闭' }}</a-button>
        <a-button :disabled="advancing" :loading="checking" @click="refresh">刷新状态</a-button>
        <a-button v-if="advancing" :disabled="pauseRequested" @click="requestPause">
          {{ pauseRequested ? '正在保存暂停状态' : '完成本批后暂停校准' }}
        </a-button>
        <a-button type="primary" :disabled="!canStart" :loading="advancing" @click="start">确认并开始校准</a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>
<style scoped>
.calibration-hint {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
  margin-block: var(--spacing-sm);
}
.calibration-run {
  padding: var(--spacing-md);
  margin-block: var(--spacing-sm);
  border: 1px solid var(--border-color);
  border-radius: var(--border-radius-md, 6px);
}
.calibration-table {
  margin-block: var(--spacing-md);
}
</style>
