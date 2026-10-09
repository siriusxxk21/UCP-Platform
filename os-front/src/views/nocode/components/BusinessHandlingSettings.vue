<script setup lang="ts">
import { ref } from 'vue'
import request from '@/utils/request'
import { getProcessDefinition } from '@/api/bpm/definition'
import { errorMessage } from '@/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { HandlingPolicy, HandlingMode } from '@/types/nocode/handling'
import DocumentExpressionEditor from './DocumentExpressionEditor.vue'

const props = defineProps<{ fields: ObjectField[]; disabled?: boolean }>()
const policy = defineModel<HandlingPolicy | null | undefined>()
const operations = [
  { value: 'create', label: '新建记录' },
  { value: 'update', label: '修改记录' }
] as const
const modes = [
  { value: 'DIRECT', label: '直接生效' },
  { value: 'APPROVAL', label: '需要审批' },
  { value: 'CONDITIONAL', label: '满足条件时审批' }
]
const processes = ref<Array<{ value: string; label: string }>>([])
const error = ref(''),
  busy = ref(false)
const fieldKey = (field: ObjectField) => field.id || field.key
function setMode(operation: 'create' | 'update', mode: HandlingMode) {
  policy.value ||= {}
  const old = policy.value[operation]
  policy.value[operation] =
    mode === 'DIRECT'
      ? { mode, variables: {} }
      : {
          mode,
          processDefinitionId: old?.processDefinitionId || null,
          variables: old?.variables || {},
          condition:
            mode === 'CONDITIONAL'
              ? old?.condition || {
                  op: 'EQ',
                  args: [
                    { op: 'FIELD', fieldId: props.fields[0] ? fieldKey(props.fields[0]) : null, args: [] },
                    { op: 'VALUE', value: '', args: [] }
                  ]
                }
              : null
        }
  if (mode !== 'DIRECT' && !processes.value.length) loadProcesses()
}
async function loadProcesses() {
  busy.value = true
  error.value = ''
  try {
    const candidates = await request.get<Array<{ id: string }>>('/bpm/process-definition/simple-list')
    const definitions = []
    for (let index = 0; index < candidates.length; index += 8)
      definitions.push(...(await Promise.all(candidates.slice(index, index + 8).map(p => getProcessDefinition(p.id)))))
    processes.value = definitions
      .filter(p => p.formType === 20 && p.formCustomViewPath === '/nocode-app/process-record')
      .map(p => ({ value: p.id, label: `${p.name} · V${p.version}` }))
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function variable(operation: 'create' | 'update', name: string, field: string | undefined) {
  const rule = policy.value?.[operation]
  if (!rule) return
  if (field) rule.variables[name] = field
  else delete rule.variables[name]
}
const variableName = ref('')
function addVariable(operation: 'create' | 'update') {
  const name = variableName.value.trim(),
    rule = policy.value?.[operation]
  if (rule && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(name) && props.fields[0]) {
    rule.variables[name] = fieldKey(props.fields[0])!
    variableName.value = ''
  }
}
</script>
<template>
  <section class="handling-settings">
    <h3>提交与审批</h3>
    <p>新建和修改分别设置办理方式，同一对象在应用和任务中的数据操作统一生效。审批期间保留原数据。</p>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-card
      v-for="operation in operations"
      :key="operation.value"
      :title="operation.label"
      size="small"
      class="handling-operation"
    >
      <a-form layout="vertical" :disabled="disabled">
        <a-form-item label="办理方式">
          <a-select
            :value="policy?.[operation.value]?.mode || 'DIRECT'"
            :options="modes"
            @change="(v: unknown) => setMode(operation.value, v as HandlingMode)"
          />
        </a-form-item>
        <template v-if="policy?.[operation.value] && policy[operation.value]!.mode !== 'DIRECT'">
          <a-form-item
            label="审批流程"
            required
            help="使用流程中心已发布的业务表单流程；查看路径为 /nocode-app/process-record。"
          >
            <a-space style="width: 100%">
              <a-select
                v-model:value="policy[operation.value]!.processDefinitionId"
                style="min-width: 280px"
                :options="processes"
                :loading="busy"
                show-search
                option-filter-prop="label"
                placeholder="选择已发布流程版本"
                @dropdown-visible-change="(open: boolean) => open && !processes.length && loadProcesses()"
              />
              <a-button :loading="busy" @click="loadProcesses">刷新流程</a-button>
            </a-space>
          </a-form-item>
          <a-form-item
            v-if="policy[operation.value]!.mode === 'CONDITIONAL' && policy[operation.value]!.condition"
            label="需要审批的条件"
            required
            help="按提交时整单计算结果判断；不满足条件时直接生效。"
          >
            <DocumentExpressionEditor
              v-model="policy[operation.value]!.condition!"
              :fields="fields"
              :details="[]"
              :allow-aggregates="false"
              :disabled="disabled"
            />
          </a-form-item>
          <a-collapse ghost>
            <a-collapse-panel key="variables" header="流程变量（可选）">
              <div v-for="(field, name) in policy[operation.value]!.variables" :key="name" class="variable-row">
                <span>{{ name }}</span>
                <a-select
                  :value="field"
                  :options="fields.map(f => ({ value: fieldKey(f), label: f.name }))"
                  @change="(v: unknown) => variable(operation.value, String(name), v as string)"
                />
                <a-button danger @click="variable(operation.value, String(name), undefined)">移除</a-button>
              </div>
              <a-space>
                <a-input v-model:value="variableName" placeholder="变量名（英文字母开头）" />
                <a-button
                  :disabled="!variableName.trim() || Object.keys(policy[operation.value]!.variables).length >= 30"
                  @click="addVariable(operation.value)"
                >
                  添加变量
                </a-button>
              </a-space>
            </a-collapse-panel>
          </a-collapse>
        </template>
      </a-form>
    </a-card>
  </section>
</template>
<style scoped>
.handling-settings p {
  color: var(--os-text-secondary, #666);
}
.handling-operation {
  margin-bottom: 16px;
}
.variable-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 10px;
}
.variable-row .ant-select {
  min-width: 200px;
}
</style>
