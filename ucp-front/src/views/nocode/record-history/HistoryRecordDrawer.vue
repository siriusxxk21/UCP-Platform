<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import dayjs from 'dayjs'
import {
  changeOperator,
  changedKeys,
  dateTriggerSourceLabel,
  historyValue,
  linkageSourceLabel,
  operationLabels
} from '@/nocode/record-history'
import type { HistoryDetail } from '@/types/nocode/record-history'
import HistoryDetailChanges from './HistoryDetailChanges.vue'
const props = defineProps<{
  open: boolean
  data?: HistoryDetail
  loading: boolean
  error: string
  tableName: string
  recordId: string
  start?: string
  end?: string
  employeeId?: string
}>()
defineEmits<{ close: []; retry: [] }>()
const tab = ref('result')
const row = computed(() => props.data?.row)
const diff = computed(() => (row.value ? changedKeys(row.value.startValues, row.value.endValues) : []))
const changes = computed(() => [...(row.value?.changes || [])].sort((a, b) => Date.parse(a.time) - Date.parse(b.time)))
const hasDetailChanges = computed(() => changes.value.some(change => change.details?.length))
const participants = computed(() => [...new Set(changes.value.map(change => change.employeeName))].join('、'))
const fieldName = (id: string) => props.data?.fields.find(f => f.id === id)?.name || id
const time = (value?: string) => (value ? dayjs(value).format('MM-DD HH:mm:ss') : '—')
watch(
  () => [props.open, props.recordId],
  () => {
    tab.value = 'result'
  }
)
</script>
<template>
  <a-drawer :open="open" title="记录变化" width="min(760px, 100vw)" class="history-drawer" @close="$emit('close')">
    <a-spin v-if="loading" tip="正在加载修改过程" />
    <a-alert v-if="error" type="error" :message="error" show-icon>
      <template #action><a-button @click="$emit('retry')">重试</a-button></template>
    </a-alert>
    <template v-if="row">
      <div class="history-record-heading">
        <h3>{{ tableName }}</h3>
        <span>记录 {{ recordId }} · {{ time(start) }} — {{ time(end) }}</span>
      </div>
      <div class="history-record-summary">
        <strong>
          {{
            row.createdInRange && row.deleted
              ? '先新增，后删除'
              : row.deleted
                ? '记录已删除'
                : row.createdInRange
                  ? '新建了一条记录'
                  : row.restored
                    ? '修改后恢复了原值'
                    : diff.length
                      ? diff.length + ' 个字段的最终值发生变化'
                      : hasDetailChanges
                        ? '包含内部明细变化'
                        : '起止值没有变化'
          }}
        </strong>
        <span>
          期间 {{ changes.length }} 次变更
          <span v-if="participants">· {{ participants }}参与</span>
        </span>
      </div>
      <a-alert
        v-if="employeeId"
        type="info"
        show-icon
        message="保留所有人的修改过程；所选人员的操作已标记，避免遗漏他人后续修改。"
      />
      <a-tabs v-model:active-key="tab">
        <a-tab-pane key="result" tab="最终变化">
          <p class="history-help">对比检索开始和结束时的值，快速看清这段时间最终改变了什么。</p>
          <div v-if="diff.length" class="history-diffs" role="table" aria-label="记录前后对比">
            <div class="history-diff-head" role="row">
              <strong role="columnheader">字段</strong>
              <strong role="columnheader">开始时</strong>
              <strong role="columnheader">结束时</strong>
            </div>
            <div v-for="key in diff" :key="key" role="row">
              <strong role="cell">{{ fieldName(key) }}</strong>
              <span role="cell" class="history-before">
                {{ row.startValues === null ? '记录不存在' : historyValue(row.startValues[key]) }}
              </span>
              <span role="cell" class="history-after">
                {{ row.endValues === null ? '记录已删除' : historyValue(row.endValues[key]) }}
              </span>
            </div>
          </div>
          <a-empty
            v-else
            :description="
              row.createdInRange && row.deleted
                ? '新增后删除，起止时都没有这条记录；过程仍然保留。'
                : row.restored
                  ? '最终值已恢复，修改过程仍保留。'
                  : hasDetailChanges
                    ? '主表起止值没有变化；明细的增删、修改及排序请查看修改过程。'
                    : '范围起止没有字段差异。'
            "
          />
          <a-button v-if="changes.length" class="history-process-button" @click="tab = 'process'">
            查看 {{ changes.length }} 次修改过程
          </a-button>
        </a-tab-pane>
        <a-tab-pane key="process" :tab="'修改过程 ' + changes.length">
          <p class="history-help">按时间先后展示；同一条记录多人协作时，可逐次查看每个人改动的内容。</p>
          <a-timeline v-if="changes.length">
            <a-timeline-item
              v-for="change in changes"
              :key="change.id"
              :color="change.operation === 'DELETE' ? 'red' : change.operation === 'CREATE' ? 'green' : 'blue'"
            >
              <div class="history-event" :class="{ 'history-selected-person': employeeId === change.employeeId }">
                <div class="history-event-heading">
                  <strong>{{ changeOperator(change) }}</strong>
                  <a-tag v-if="change.source?.kind === 'TASK_ENTRY'" color="purple">
                    任务中心 · {{ change.source.name }}
                  </a-tag>
                  <a-tag v-if="change.source?.kind === 'AUTOMATION'" color="cyan">
                    自动更新 · {{ change.source.name }} · V{{ change.source.version }}
                  </a-tag>
                  <a-tag v-if="change.source?.kind === 'CAPTURE_VALUES'" color="blue">
                    留存计算结果 · {{ change.source.name }} · V{{ change.source.version }}
                  </a-tag>
                  <a-tag v-if="change.source?.kind === 'ORDERED_CALCULATION'" color="cyan">有序计算联动</a-tag>
                  <a-tag v-if="linkageSourceLabel(change.source)" color="geekblue">
                    {{ linkageSourceLabel(change.source) }}
                  </a-tag>
                  <a-tag v-if="dateTriggerSourceLabel(change.source)" color="purple">
                    {{ dateTriggerSourceLabel(change.source) }}
                  </a-tag>
                  <a-tag>{{ operationLabels[change.operation] }}</a-tag>
                  <a-tag v-if="change.source?.relatedUpdate" color="blue">关联数据更新</a-tag>
                  <a-tag v-if="employeeId === change.employeeId" color="purple">所选人员</a-tag>
                  <span>{{ time(change.time) }}</span>
                </div>
                <p v-if="change.source?.relatedUpdate">主记录字段保持不变，本次保存更新了关联对象中的记录。</p>
                <div v-for="key in changedKeys(change.before, change.after)" :key="key" class="history-event-diff">
                  <strong>{{ change.fields.find(f => f.id === key)?.name || fieldName(key) }}</strong>
                  <div>
                    <span class="history-before">
                      {{ change.before === null ? '记录不存在' : historyValue(change.before[key]) }}
                    </span>
                    <span aria-label="变为">→</span>
                    <span class="history-after">
                      {{ change.after === null ? '记录已删除' : historyValue(change.after[key]) }}
                    </span>
                  </div>
                </div>
                <HistoryDetailChanges v-if="change.details?.length" :groups="change.details" />
                <span
                  v-if="!changedKeys(change.before, change.after).length && !change.details?.length"
                  class="history-help"
                >
                  主表字段未变；可能涉及其他已留存的单据内容。
                </span>
              </div>
            </a-timeline-item>
          </a-timeline>
          <a-empty v-else description="该记录在检索范围内没有变更" />
        </a-tab-pane>
        <a-tab-pane key="record" tab="主表快照">
          <p>{{ row.deleted ? '删除前留存内容' : '截至检索结束时刻的内容' }}</p>
          <p class="history-help">此处展示主表字段；明细的逐行变化见“修改过程”，按当次留存及当前权限展示。</p>
          <a-descriptions bordered :column="1" size="small">
            <a-descriptions-item v-for="field in data?.fields" :key="field.id" :label="field.name">
              {{ historyValue(row.values[field.id]) }}
            </a-descriptions-item>
          </a-descriptions>
        </a-tab-pane>
      </a-tabs>
    </template>
  </a-drawer>
</template>
<style scoped src="./record-history.css"></style>
