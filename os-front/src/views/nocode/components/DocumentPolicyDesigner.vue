<script setup lang="ts">
import { v4 as uuid } from 'uuid'
import { computed, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import type { SaveDesign, ObjectDetail } from '@/types/nocode/data-center'
import type { DocumentPolicy, DocumentRule, DocumentScope } from '@/types/nocode/document-policy'
import { FieldType, MemberState } from '@/types/nocode/enums'
import DocumentExpressionEditor from './DocumentExpressionEditor.vue'
import BusinessHandlingSettings from './BusinessHandlingSettings.vue'
import { lifecycleRemovalImpact, removeRetiredLifecycleStates, synchronizeLifecycle } from '@/nocode/document-lifecycle'
const props = defineProps<{ design: SaveDesign; disabled?: boolean }>()
const policy = defineModel<DocumentPolicy | null | undefined>()
const details = computed(() => props.design.details.filter(d => d.state === MemberState.ACTIVE))
const key = (d: ObjectDetail) => d.id || `detail:${d.code}`
const fieldKey = (f: SaveDesign['draft']['fields'][number]) => f.id || f.key
const fields = computed(() =>
  props.design.draft.fields.filter(f => props.design.fieldOptions[f.key]?.state !== MemberState.INACTIVE)
)
const fieldOptions = computed(() => fields.value.map(f => ({ value: fieldKey(f), label: f.name })))
const detailOptions = computed(() => details.value.map(d => ({ value: key(d), label: d.name })))
const scopes: Array<{ value: DocumentScope; label: string }> = [
  { value: 'FIELD', label: '字段' },
  { value: 'ROW', label: '每一行明细' },
  { value: 'DETAIL', label: '明细集合' },
  { value: 'DOCUMENT', label: '整张单据' }
]
function enable(enabled: boolean) {
  policy.value = enabled ? { rules: [], lifecycle: null } : null
}
function addRule() {
  policy.value?.rules.push({
    id: `rule_${uuid().replaceAll('-', '')}`,
    name: '新规则',
    scope: 'DOCUMENT',
    detailId: null,
    fieldId: null,
    when: null,
    assertion: {
      op: 'EQ',
      args: [
        { op: 'VALUE', value: 0, args: [] },
        { op: 'VALUE', value: 0, args: [] }
      ]
    },
    message: '请检查填写内容'
  })
}
function ruleFields(rule: DocumentRule) {
  const row = rule.scope === 'ROW' || (rule.scope === 'FIELD' && rule.detailId)
  return [...fields.value, ...(row ? details.value.find(d => key(d) === rule.detailId)?.fields || [] : [])]
}
function changeScope(rule: DocumentRule) {
  rule.fieldId = null
  rule.detailId = rule.scope === 'ROW' || rule.scope === 'DETAIL' ? detailOptions.value[0]?.value || null : null
}
function stateField(fieldId: string | undefined) {
  if (props.disabled) return
  if (!policy.value) return
  if (fieldId === policy.value.lifecycle?.fieldId) return
  if (policy.value.lifecycle) {
    Modal.confirm({
      title: fieldId ? '更换受控状态字段？' : '停用受控状态？',
      content: '当前状态的动作和锁定配置将被移除。若只是调整选项，请保留此字段并使用下方增量同步。',
      onOk: () => replaceStateField(fieldId)
    })
    return
  }
  replaceStateField(fieldId)
}
function replaceStateField(fieldId: string | undefined) {
  if (!policy.value || props.disabled) return
  if (!fieldId) {
    policy.value.lifecycle = null
    return
  }
  const f = fields.value.find(f => fieldKey(f) === fieldId)
  if (!f) return
  const options = props.design.fieldOptions[f.key]?.options || []
  policy.value.lifecycle = {
    fieldId,
    initialState: options[0]?.code || '',
    states: options.map(o => ({ code: o.code, name: o.label, lockedFields: [], lockedDetails: [], allowDelete: true })),
    actions: []
  }
}
const lifecycleOptions = computed(() => {
  const f = fields.value.find(f => fieldKey(f) === policy.value?.lifecycle?.fieldId)
  return f ? props.design.fieldOptions[f.key]?.options || [] : []
})
watch(
  () => [policy.value?.lifecycle?.fieldId, lifecycleOptions.value],
  () => {
    if (policy.value?.lifecycle && !props.disabled)
      policy.value.lifecycle = synchronizeLifecycle(policy.value.lifecycle, lifecycleOptions.value)
  },
  { deep: true, immediate: true }
)
const removalImpact = computed(() =>
  policy.value?.lifecycle ? lifecycleRemovalImpact(policy.value.lifecycle, lifecycleOptions.value) : null
)
const stateOptions = computed(() => lifecycleOptions.value.map(o => ({ value: o.code, label: o.label })))
function removeRetiredStates() {
  const current = policy.value?.lifecycle
  const impact = removalImpact.value
  if (props.disabled || !current || !impact?.removed.length || impact.initial || impact.actions.length) return
  Modal.confirm({
    title: '移除已失效状态的配置？',
    content: `将移除 ${impact.removed.map(state => state.name).join('、')} 的锁定设置；已保留的状态和动作不变。`,
    onOk: () => {
      if (policy.value?.lifecycle === current && !props.disabled)
        policy.value.lifecycle = removeRetiredLifecycleStates(current, lifecycleOptions.value)
    }
  })
}
</script>
<template>
  <div class="document-policy">
    <div class="policy-toolbar">
      <div>
        <h3>整单规则与状态</h3>
        <p class="muted">主表与内部明细统一校验。规则不通过时，整张单据不保存。</p>
      </div>
      <a-switch
        :checked="!!policy"
        :disabled="disabled"
        checked-children="已启用"
        un-checked-children="未启用"
        @change="(v: boolean | string | number) => enable(!!v)"
      />
    </div>
    <template v-if="policy">
      <BusinessHandlingSettings v-model="policy.handling" :fields="fields" :disabled="disabled" />
      <a-divider />
      <a-alert
        type="info"
        show-icon
        message="规则对所有保存入口生效。可设置生效条件，例如仅在登记时检查借贷平衡。"
        class="notice"
      />
      <div class="policy-toolbar">
        <strong>校验规则（{{ policy.rules.length }}/100）</strong>
        <a-button :disabled="disabled || policy.rules.length >= 100" @click="addRule">添加规则</a-button>
      </div>
      <a-empty v-if="!policy.rules.length" description="尚未配置校验规则" />
      <a-collapse v-else>
        <a-collapse-panel
          v-for="(rule, index) in policy.rules"
          :key="rule.id"
          :header="`${rule.name} · ${scopes.find(s => s.value === rule.scope)?.label}`"
        >
          <a-form layout="vertical" :disabled="disabled">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
                <a-form-item label="规则名称" required>
                  <a-input v-model:value="rule.name" :maxlength="128" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="作用范围">
                  <a-select v-model:value="rule.scope" :options="scopes" @change="changeScope(rule)" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="16">
              <a-col v-if="rule.scope !== 'DOCUMENT'" :xs="24" :md="12">
                <a-form-item label="所在明细">
                  <a-select
                    v-model:value="rule.detailId"
                    :allow-clear="rule.scope === 'FIELD'"
                    :options="detailOptions"
                    placeholder="主表字段可留空"
                    @change="rule.fieldId = null"
                  />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="错误定位字段" :required="rule.scope === 'FIELD'">
                  <a-select
                    v-model:value="rule.fieldId"
                    allow-clear
                    :options="ruleFields(rule).map(f => ({ value: fieldKey(f), label: f.name }))"
                    placeholder="可留空，定位到整单或明细组"
                  />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="生效条件">
              <a-switch
                :checked="!!rule.when"
                checked-children="满足条件时"
                un-checked-children="始终生效"
                @change="
                  (v: boolean | string | number) =>
                    (rule.when = v
                      ? {
                          op: 'EQ',
                          args: [
                            { op: 'FIELD', fieldId: fields[0] ? fieldKey(fields[0]) : null, args: [] },
                            { op: 'VALUE', value: '', args: [] }
                          ]
                        }
                      : null)
                "
              />
              <DocumentExpressionEditor
                v-if="rule.when"
                v-model="rule.when"
                :fields="ruleFields(rule)"
                :details="details"
                :disabled="disabled"
                class="condition"
              />
            </a-form-item>
            <a-form-item label="必须满足" required>
              <DocumentExpressionEditor
                v-model="rule.assertion"
                :fields="ruleFields(rule)"
                :details="details"
                :disabled="disabled"
              />
            </a-form-item>
            <a-form-item label="不满足时提示" required>
              <a-input v-model:value="rule.message" :maxlength="500" />
            </a-form-item>
            <a-button danger :disabled="disabled" @click="policy!.rules.splice(index, 1)">移除规则</a-button>
          </a-form>
        </a-collapse-panel>
      </a-collapse>
      <a-divider />
      <h3>受控状态</h3>
      <p class="muted">状态由下方动作切换。可设置每个状态下不能修改的字段、明细，以及是否允许删除。</p>
      <a-form layout="vertical" :disabled="disabled">
        <a-form-item label="状态字段">
          <a-select
            :value="policy.lifecycle?.fieldId"
            allow-clear
            placeholder="选择主表单选字段，留空则不启用状态控制"
            :options="fields.filter(f => f.type === FieldType.SELECT).map(f => ({ value: fieldKey(f), label: f.name }))"
            @change="(v: unknown) => stateField(v as string | undefined)"
          />
        </a-form-item>
      </a-form>
      <template v-if="policy.lifecycle">
        <a-alert
          v-if="removalImpact?.removed.length"
          type="warning"
          show-icon
          class="notice"
          :message="'状态选项已移除：' + removalImpact.removed.map(s => s.name).join('、')"
          :description="
            [
              removalImpact.initial ? '请重新选择初始状态。' : '',
              removalImpact.actions.length
                ? '请调整或明确移除受影响动作：' + removalImpact.actions.map(a => a.name).join('、') + '。'
                : '',
              '处理后移除失效状态配置，再保存对象；其余状态和动作会保留。'
            ].join('')
          "
        >
          <template #action>
            <a-button
              :disabled="disabled || removalImpact.initial || !!removalImpact.actions.length"
              @click="removeRetiredStates"
            >
              移除已失效状态配置
            </a-button>
          </template>
        </a-alert>
        <a-form layout="vertical" :disabled="disabled">
          <a-form-item label="新建时的状态">
            <a-select v-model:value="policy.lifecycle.initialState" :options="stateOptions" />
          </a-form-item>
        </a-form>
        <a-collapse>
          <a-collapse-panel v-for="state in policy.lifecycle.states" :key="state.code" :header="state.name">
            <a-form layout="vertical" :disabled="disabled">
              <a-form-item label="锁定主表字段">
                <a-select v-model:value="state.lockedFields" mode="multiple" :options="fieldOptions" />
              </a-form-item>
              <a-form-item label="锁定内部明细">
                <a-select v-model:value="state.lockedDetails" mode="multiple" :options="detailOptions" />
              </a-form-item>
              <a-checkbox v-model:checked="state.allowDelete">允许删除该状态下的整张单据</a-checkbox>
            </a-form>
          </a-collapse-panel>
        </a-collapse>
        <div class="policy-toolbar actions-heading">
          <strong>状态动作</strong>
          <a-button
            :disabled="disabled || policy.lifecycle.actions.length >= 50"
            @click="
              policy.lifecycle.actions.push({
                code: 'action_' + (policy.lifecycle.actions.length + 1),
                name: '新动作',
                fromStates: [],
                toState: policy.lifecycle.initialState,
                permission: 'UPDATE'
              })
            "
          >
            添加动作
          </a-button>
        </div>
        <a-card v-for="(action, index) in policy.lifecycle.actions" :key="index" size="small" class="action-card">
          <a-form layout="vertical" :disabled="disabled">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
                <a-form-item label="动作名称"><a-input v-model:value="action.name" :maxlength="128" /></a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="动作编码"><a-input v-model:value="action.code" :maxlength="80" /></a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
                <a-form-item label="允许的来源状态">
                  <a-select v-model:value="action.fromStates" mode="multiple" :options="stateOptions" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item label="目标状态">
                  <a-select v-model:value="action.toState" :options="stateOptions" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="所需操作权限">
              <a-select
                v-model:value="action.permission"
                :options="[
                  { value: 'CREATE', label: '新建权限' },
                  { value: 'UPDATE', label: '修改权限' }
                ]"
              />
            </a-form-item>
            <a-button danger :disabled="disabled" @click="policy!.lifecycle!.actions.splice(index, 1)">
              移除动作
            </a-button>
          </a-form>
        </a-card>
      </template>
    </template>
  </div>
</template>
<style scoped>
.document-policy {
  max-width: 1080px;
}
.policy-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
h3 {
  margin: 0 0 6px;
}
.muted {
  color: var(--os-text-secondary, #666);
}
.notice,
.action-card {
  margin-bottom: 16px;
}
.condition,
.actions-heading {
  margin-top: 16px;
}
</style>
