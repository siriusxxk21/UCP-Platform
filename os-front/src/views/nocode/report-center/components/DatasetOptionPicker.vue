<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { DataScope, ScopeCondition } from '@/types/nocode/data-scope'
import type { DatasetOptionPage } from '@/types/nocode/report-center'
const props = defineProps<{
  datasetId: string
  condition: ScopeCondition
  multiple: boolean
  filters: DataScope
  disabled?: boolean
  booleanField?: boolean
}>()
const emit = defineEmits<{ select: [value: unknown, operator: ScopeCondition['operator']] }>()
const api = useNocodePlatform().reportCenter
const open = ref(false),
  busy = ref(false),
  error = ref(''),
  search = ref(''),
  page = ref(1)
const result = ref<DatasetOptionPage>(),
  selected = ref<string[]>([])
let sequence = 0
async function load(pageNo = 1) {
  selected.value = []
  const request = ++sequence
  busy.value = true
  error.value = ''
  result.value = undefined
  page.value = pageNo
  try {
    const response = await api.options({
      datasetId: props.datasetId,
      preview: true,
      fieldId: props.condition.fieldId,
      filters: props.filters,
      pageNo,
      pageSize: 20,
      search: search.value
    })
    if (request === sequence) result.value = response
  } catch (e) {
    if (request === sequence) error.value = errorMessage(e)
  } finally {
    if (request === sequence) busy.value = false
  }
}
function show() {
  selected.value = []
  search.value = ''
  open.value = true
  void load()
}
function close() {
  open.value = false
  sequence++
  result.value = undefined
  busy.value = false
}
function apply() {
  if (!selected.value.length || !result.value || busy.value || error.value) return
  // 编码以区分 NULL、空字符串和字符串“null”，绝不按标签回填。
  const values = selected.value.map(value => JSON.parse(value) as string | null)
  if (values.includes(null)) emit('select', null, props.condition.operator === 'neq' ? 'notNull' : 'isNull')
  else {
    const typed = values.map(value => (props.booleanField ? value === 'true' : value))
    emit('select', props.multiple ? typed : typed[0], props.condition.operator)
  }
  close()
}
watch(
  () => [props.datasetId, props.condition.fieldId, props.filters, props.disabled],
  () => {
    if (open.value) close()
  },
  { deep: true }
)
onScopeDispose(() => {
  sequence++
})
</script>
<template>
  <a-button :disabled="disabled" @click="show">选择候选</a-button>
  <OsModalForm
    :open="open"
    title="选择筛选候选"
    :loading="busy"
    :wrap-form="false"
    :width="600"
    ok-text="使用所选值"
    @ok="apply"
    @cancel="close"
  >
    <template #footer>
      <a-button @click="close">取消</a-button>
      <a-button type="primary" :disabled="busy || !selected.length || !!error || !result" @click="apply">
        使用所选值
      </a-button>
    </template>
    <template #formItems>
      <a-alert
        type="info"
        message="仅显示当前可读取的实际值，保留其他筛选与固定条件；当前字段自身的临时条件不限制候选。"
        show-icon
      />
      <a-input-search
        v-model:value="search"
        aria-label="搜索候选"
        placeholder="搜索值或显示名"
        :maxlength="100"
        enter-button="搜索"
        @search="load(1)"
      />
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-spin :spinning="busy" :delay="300" tip="加载中...">
        <a-checkbox-group v-if="multiple" v-model:value="selected" class="candidate-values">
          <a-checkbox
            v-for="item in result?.list"
            :key="JSON.stringify(item.value)"
            :value="JSON.stringify(item.value)"
            :disabled="item.value === null ? selected.some(v => v !== 'null') : selected.includes('null')"
          >
            {{ item.label || '空字符串' }}
            <span v-if="item.value !== null && item.label !== item.value">（{{ item.value }}）</span>
          </a-checkbox>
        </a-checkbox-group>
        <a-radio-group
          v-else
          :value="selected[0]"
          class="candidate-values"
          @change="(e: { target: { value: string } }) => (selected = [e.target.value])"
        >
          <a-radio v-for="item in result?.list" :key="JSON.stringify(item.value)" :value="JSON.stringify(item.value)">
            {{ item.label || '空字符串' }}
            <span v-if="item.value !== null && item.label !== item.value">（{{ item.value }}）</span>
          </a-radio>
        </a-radio-group>
        <a-empty v-if="!busy && !error && !result?.list.length" description="没有匹配的可读候选" />
      </a-spin>
      <a-pagination
        v-if="result"
        :current="page"
        :page-size="20"
        :total="result.total"
        :show-size-changer="false"
        :show-total="(total: number) => `共 ${total} 个值`"
        @change="load"
      />
    </template>
  </OsModalForm>
</template>
<style scoped>
.candidate-values {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin-block: var(--spacing-md);
}
</style>
