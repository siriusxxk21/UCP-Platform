<script setup lang="ts">
import { computed, provide } from 'vue'
import type { PublishedObject } from '@/types/nocode/application'
import type { AutomationAssignment, AutomationConfig } from '@/types/nocode/automation'
import { AutomationMode } from '@/types/nocode/automation'
import {
  MAX_OFFSET_DAYS,
  automationDateFields,
  automationEvents,
  automationFields,
  automationKinds,
  automationModes,
  automationRelations,
  automationSourceFields,
  automationTargetFields,
  dateTriggerTargets,
  newAutomationAssignment,
  resetAutomationAssignment
} from '@/nocode/automation'
import { emptyScope } from '@/nocode/data-scope'
import { selectionPreviewKey } from '@/nocode/selection'
import { RELATIVE_BLOCKED, relativeDateField } from '@/nocode/relative-date'
import DataScopeEditor from '../../components/DataScopeEditor.vue'
import RelativeDateValue from '../../components/RelativeDateValue.vue'
import AutomationValueEditor from './AutomationValueEditor.vue'

const props = defineProps<{
  objects: Record<string, PublishedObject>
  applicationId?: string
  readOnly?: boolean
}>()
const config = defineModel<AutomationConfig>({ required: true })
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    }))
  }))
)
const objectOptions = computed(() =>
  Object.values(props.objects).map(o => ({ value: o.objectId, label: o.definition.objectName }))
)
const source = computed(() => props.objects[config.value.objectId]?.definition)
const target = computed(() => props.objects[config.value.targetObjectId]?.definition)
const relations = computed(() => automationRelations(config.value.objectId, props.objects))
const relationKey = computed(() => `${config.value.binding.direction}:${config.value.binding.relationId}`)
const targetFields = computed(() => automationTargetFields(config.value, props.objects))
const dated = computed(() => config.value.mode === AutomationMode.DATE)
const dateFields = computed(() =>
  automationDateFields(source.value).map(f => ({
    value: f.id || '',
    label: f.type === 'DATETIME' ? `${f.name}（日期时间，按日期部分）` : f.name
  }))
)
const dateTargets = computed(() => dateTriggerTargets(config.value.objectId, props.objects))
const dateTargetKey = computed(() =>
  config.value.targetObjectId ? `${config.value.binding.direction}:${config.value.binding.relationId ?? ''}` : undefined
)
const offsetDirections = [
  { value: 'ON', label: '日期当天' },
  { value: 'BEFORE', label: '日期之前' },
  { value: 'AFTER', label: '日期之后' }
]
const offsetDirection = computed({
  get: () => (!config.value.offsetDays ? 'ON' : config.value.offsetDays < 0 ? 'BEFORE' : 'AFTER'),
  set: (next: string) => {
    const days = Math.abs(config.value.offsetDays || 0) || 1
    config.value.offsetDays = next === 'ON' ? 0 : next === 'BEFORE' ? -days : days
  }
})
const offsetDays = computed({
  get: () => Math.abs(config.value.offsetDays || 0),
  set: (next: number | null) => {
    const days = Math.max(1, Math.min(MAX_OFFSET_DAYS, Math.trunc(Number(next) || 1)))
    config.value.offsetDays = offsetDirection.value === 'BEFORE' ? -days : days
  }
})
function sourceChanged() {
  if (dated.value) {
    config.value.targetObjectId = config.value.objectId
    config.value.binding = { relationId: null, direction: 'SELF' }
    config.value.dateFieldId = null
    config.value.conditions = null
    config.value.assignments = []
    return
  }
  config.value.targetObjectId = ''
  config.value.binding = { relationId: '', direction: 'OUTGOING' }
  config.value.conditions = null
  config.value.assignments = []
}
function dateTargetChanged(key: string) {
  const target = dateTargets.value.find(r => r.value === key)
  if (!target) return
  config.value.targetObjectId = target.targetObjectId
  config.value.binding = { ...target.binding }
  config.value.assignments = []
}
function relationChanged(key: string) {
  const relation = relations.value.find(r => r.value === key)
  if (!relation) return
  config.value.targetObjectId = relation.targetObjectId
  config.value.binding = { ...relation.binding }
  config.value.assignments = []
}
function modeChanged() {
  config.value.events =
    config.value.mode === AutomationMode.MAINTAIN ? ['CREATE', 'UPDATE', 'DELETE'] : ['CREATE', 'UPDATE']
  config.value.assignments.forEach(a => resetAutomationAssignment(a, config.value.mode))
}
function kindChanged(assignment: AutomationAssignment) {
  assignment.sourceFieldId = null
  assignment.value = null
  assignment.emptyValue = null
}
const fieldsFor = (assignment: AutomationAssignment) => automationSourceFields(config.value, assignment, props.objects)
/**
 * 条件里的日期字段可选相对日期；持续维护的结果写进记录、只在来源变化时重算，相对日期置灰并写明原因。
 * 事件赋值按事件发生当天判断；按日期自动执行按正在处理的那一天判断（补做漏掉的日子时按被补做的那一天），二者都可用。
 */
const conditionDate = (fieldId: string) => relativeDateField(source.value?.fields.find(f => f.id === fieldId)?.type)
const relativeBlocked = computed(() =>
  config.value.mode === AutomationMode.MAINTAIN ? RELATIVE_BLOCKED.maintain : null
)
const kindsFor = (assignment: AutomationAssignment) =>
  automationKinds(
    config.value,
    targetFields.value.find(f => f.id === assignment.fieldId),
    target.value
  )
</script>
<template>
  <div class="automation-editor">
    <a-form-item label="规则状态">
      <a-switch
        v-model:checked="config.enabled"
        :disabled="readOnly"
        checked-children="启用"
        un-checked-children="停用"
      />
    </a-form-item>
    <template v-if="dated">
      <a-alert
        type="info"
        show-icon
        message="每天到了记录上的日期，自动执行一次下方的赋值。"
        description="每天 00:10（系统时间，日本时间 01:10）执行；当天晚些时候新录入的、日期为今天的记录约 10 分钟内补上。每条记录每天只执行一次，执行后人工修改的值不会被改回。从发布当天开始生效，不会补做发布前已经过去的日期。"
      />
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="来源数据对象" required>
            <a-select
              v-model:value="config.objectId"
              :options="objectOptions"
              :disabled="readOnly"
              show-search
              option-filter-prop="label"
              @change="sourceChanged"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="按哪个日期执行" required>
            <a-select
              v-model:value="config.dateFieldId"
              :options="dateFields"
              :disabled="readOnly || !source"
              placeholder="选择日期字段"
              show-search
              option-filter-prop="label"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <p v-if="source && !dateFields.length" class="hint">来源数据对象没有可用的日期或日期时间字段。</p>
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="执行日" required>
            <a-space>
              <a-select
                v-model:value="offsetDirection"
                :options="offsetDirections"
                :disabled="readOnly"
                style="width: 120px"
              />
              <a-input-number
                v-if="offsetDirection !== 'ON'"
                v-model:value="offsetDays"
                :min="1"
                :max="MAX_OFFSET_DAYS"
                :precision="0"
                :disabled="readOnly"
                addon-after="天"
              />
            </a-space>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="要更新的记录" required>
            <a-select
              :value="dateTargetKey"
              :options="dateTargets"
              :disabled="readOnly || !source"
              placeholder="本条记录，或通过关系找到的记录"
              show-search
              option-filter-prop="label"
              @change="dateTargetChanged"
            />
          </a-form-item>
        </a-col>
      </a-row>
    </template>
    <template v-else>
      <a-form-item label="更新方式" required>
        <a-radio-group
          v-model:value="config.mode"
          :options="automationModes"
          :disabled="readOnly"
          @change="modeChanged"
        />
      </a-form-item>
      <a-alert
        type="info"
        show-icon
        :message="
          config.mode === AutomationMode.MAINTAIN
            ? '根据当前有效关联记录重新计算，自动处理新增、修改、删除和更换关联。'
            : '在选定事件发生时执行一次赋值；后续撤销或解除关联不会自动恢复原值。'
        "
        :description="
          config.mode === AutomationMode.MAINTAIN
            ? '条件决定哪些来源记录有效。目标字段由规则维护，不能手工覆盖；没有有效记录时，使用下方设置的结果。'
            : '修改事件使用保存后的值，删除事件使用删除前的值。需要持续随关联数据变化时，请选择“持续维护关联结果”。'
        "
      />
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="来源数据对象" required>
            <a-select
              v-model:value="config.objectId"
              :options="objectOptions"
              :disabled="readOnly"
              show-search
              option-filter-prop="label"
              @change="sourceChanged"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="通过关系找到要更新的记录" required>
            <a-select
              :value="config.binding.relationId ? relationKey : undefined"
              :options="relations"
              :disabled="readOnly"
              placeholder="选择关联关系"
              show-search
              option-filter-prop="label"
              @change="relationChanged"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <p v-if="source && !relations.length" class="hint">
        当前没有可用关系。请在数据中心配置主表对象引用，发布后在应用中引用来源和目标对象。
      </p>
    </template>
    <a-form-item v-if="config.mode === AutomationMode.EVENT" label="触发事件" required>
      <a-checkbox-group v-model:value="config.events" :options="[...automationEvents]" :disabled="readOnly" />
    </a-form-item>
    <a-form-item
      :label="config.mode === AutomationMode.MAINTAIN ? '哪些来源记录参与维护' : '来源记录满足以下条件时执行'"
    >
      <a-checkbox
        :checked="!!config.conditions"
        :disabled="readOnly"
        @change="config.conditions = $event.target.checked ? emptyScope() : null"
      >
        设置条件
      </a-checkbox>
      <p class="hint">不设置条件时，所有关联的来源记录都符合条件。</p>
      <DataScopeEditor
        v-if="config.conditions && source"
        v-model="config.conditions"
        :fields="automationFields(source)"
        :readonly="readOnly"
      >
        <template #value="{ condition, multiple }">
          <RelativeDateValue
            v-if="conditionDate(condition.fieldId)"
            v-model="condition.value"
            :operator="condition.operator"
            :multiple="multiple"
            :disabled="readOnly"
            :blocked-reason="relativeBlocked"
          >
            <AutomationValueEditor
              v-model="condition.value"
              :definition="source"
              :field-id="condition.fieldId"
              :application-id="applicationId"
              :multiple="multiple"
              :disabled="readOnly"
              placeholder="选择或填写条件值"
            />
          </RelativeDateValue>
          <AutomationValueEditor
            v-else
            v-model="condition.value"
            :definition="source"
            :field-id="condition.fieldId"
            :application-id="applicationId"
            :multiple="multiple"
            :disabled="readOnly"
            placeholder="选择或填写条件值"
          />
        </template>
      </DataScopeEditor>
      <p v-if="config.conditions && config.mode === AutomationMode.MAINTAIN" class="hint" data-relative-hint>
        {{ RELATIVE_BLOCKED.maintain }}
      </p>
      <p v-else-if="config.conditions && dated" class="hint" data-relative-hint>
        日期条件可选「相对日期」（今天、本周、过去 N
        天等）：按正在执行的那一天判断；补做停机期间漏掉的日子时，按被补做的那一天判断（例：补做 10 月 1 日时「本周」=
        10 月 1 日所在的那一周）。
      </p>
      <p v-else-if="config.conditions" class="hint" data-relative-hint>
        日期条件可选「相对日期」（今天、本周、过去 N 天等）：按事件发生当天判断，执行后不会因为日期变化重新判断。
      </p>
    </a-form-item>
    <a-divider orientation="left">{{ target ? `更新“${target.objectName}”的字段` : '更新目标字段' }}</a-divider>
    <section v-for="(assignment, index) in config.assignments" :key="index" class="assignment">
      <div class="assignment-heading">
        <strong>字段赋值 {{ index + 1 }}</strong>
        <a-button type="link" danger :disabled="readOnly" @click="config.assignments.splice(index, 1)">
          移除赋值
        </a-button>
      </div>
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="目标字段" required>
            <a-select
              v-model:value="assignment.fieldId"
              :disabled="readOnly"
              :options="
                targetFields
                  .filter(f => f.id === assignment.fieldId || !config.assignments.some(a => a.fieldId === f.id))
                  .map(f => ({ value: f.id!, label: f.name }))
              "
              show-search
              option-filter-prop="label"
              placeholder="选择要更新的字段"
              @change="resetAutomationAssignment(assignment, config.mode)"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="取值方式" required>
            <a-select
              v-model:value="assignment.kind"
              :disabled="readOnly || !assignment.fieldId"
              :options="kindsFor(assignment)"
              @change="kindChanged(assignment)"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item v-if="['FIELD', 'SUM', 'MIN', 'MAX'].includes(assignment.kind)" label="来源字段" required>
        <a-select
          v-model:value="assignment.sourceFieldId"
          :disabled="readOnly"
          :options="fieldsFor(assignment).map(f => ({ value: f.id!, label: f.name }))"
          show-search
          option-filter-prop="label"
          placeholder="选择类型兼容的来源字段"
        />
      </a-form-item>
      <template v-if="target && assignment.fieldId">
        <a-form-item
          v-if="['VALUE', 'EXISTS'].includes(assignment.kind)"
          :label="assignment.kind === 'EXISTS' ? '存在有效关联记录时' : '写入的值'"
        >
          <AutomationValueEditor
            v-model="assignment.value"
            :definition="target"
            :field-id="assignment.fieldId"
            :application-id="applicationId"
            :disabled="readOnly"
          />
        </a-form-item>
        <a-form-item v-if="['EXISTS', 'MIN', 'MAX'].includes(assignment.kind)" label="没有有效记录时">
          <AutomationValueEditor
            v-model="assignment.emptyValue"
            :definition="target"
            :field-id="assignment.fieldId"
            :application-id="applicationId"
            :disabled="readOnly"
          />
        </a-form-item>
        <p v-if="['COUNT', 'SUM'].includes(assignment.kind)" class="hint">没有有效关联记录时写入 0。</p>
      </template>
    </section>
    <a-button
      :disabled="readOnly || !targetFields.length"
      @click="config.assignments.push(newAutomationAssignment(config.mode))"
    >
      添加更新字段
    </a-button>
    <a-alert
      v-if="dated"
      type="info"
      message="保存应用草稿并发布后生效"
      description="由系统执行，不受某个人的权限限制；变更记录里显示为「系统」。某一条更新失败（例如目标记录正在审批中）不影响其他记录，失败原因可在业务动作列表的「最近执行」里查看。同一字段可以同时被多条「事件赋值」「按日期」规则写入，后执行的为准，也可以人工修改。"
      show-icon
    />
    <a-alert
      v-else
      type="info"
      message="保存应用草稿并发布后生效"
      description="规则监听共享对象在各应用中的数据变化。操作人须在规则所属应用内拥有完整的来源记录读取权限，以及目标记录和字段的修改权限；权限不足或更新失败时，本次保存整体不生效。"
      show-icon
    />
    <p v-if="config.mode === AutomationMode.MAINTAIN" class="hint">
      发布不会批量改写已有记录；新建目标会初始化，已有目标在来源变化或再次保存时重算。
    </p>
    <p v-if="dated" class="hint">一条来源记录最多更新 200 条目标记录；超限时这一条记为失败。</p>
    <p v-else class="hint">
      当前支持主表之间一层对象引用。单次最多更新 200 条目标记录，每条目标最多统计 10000
      条来源记录；超限时本次保存整体不生效。
    </p>
  </div>
</template>
<style scoped>
.automation-editor {
  display: grid;
  gap: 16px;
}
.automation-editor :deep(.ant-form-item) {
  margin-bottom: 8px;
}
.hint {
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
  margin: 8px 0 0;
}
.assignment {
  padding: 16px;
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 8px;
}
.assignment-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
</style>
