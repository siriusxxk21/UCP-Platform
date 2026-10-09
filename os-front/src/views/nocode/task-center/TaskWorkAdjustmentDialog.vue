<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import { taskWorkRuleError, taskWorkRuleSummary } from '@/nocode/task-work-rule'
import TaskWorkDurationInput from './TaskWorkDurationInput.vue'

const props = defineProps<{ entry: TaskWorkEntryConfig | null }>()
const emit = defineEmits<{ cancel: []; save: [adjustment: number] }>()
const mode = ref<'KEEP' | 'ADD' | 'SUBTRACT'>('KEEP')
const magnitude = ref<number | null>(null)
watch(
  () => props.entry,
  entry => {
    const adjustment = entry?.workRule?.adjustmentMinutes || 0
    mode.value = adjustment > 0 ? 'ADD' : adjustment < 0 ? 'SUBTRACT' : 'KEEP'
    magnitude.value = Math.abs(adjustment) || null
  },
  { immediate: true }
)
const adjustment = computed(() =>
  mode.value === 'KEEP' ? 0 : (magnitude.value || 0) * (mode.value === 'SUBTRACT' ? -1 : 1)
)
const nextRule = computed(() =>
  props.entry?.workRule ? { ...props.entry.workRule, adjustmentMinutes: adjustment.value } : null
)
const error = computed(() => taskWorkRuleError(nextRule.value))
function save() {
  if (!nextRule.value || error.value) return
  emit('save', adjustment.value)
}
</script>
<template>
  <OsModalForm
    :open="!!entry"
    title="调整本次工时"
    :width="560"
    :wrap-form="false"
    :allow-switch-display="false"
    @cancel="emit('cancel')"
  >
    <template #formItems>
      <div v-if="entry?.workRule" class="work-adjustment">
        <strong>{{ entry.name }}</strong>
        <div class="work-adjustment__baseline">
          <span>模板工时</span>
          <span>{{ taskWorkRuleSummary({ ...entry.workRule, adjustmentMinutes: 0 }) }}</span>
        </div>
        <a-form layout="vertical">
          <a-form-item label="本次调整">
            <a-radio-group v-model:value="mode" option-type="button" button-style="solid">
              <a-radio-button value="KEEP">沿用模板</a-radio-button>
              <a-radio-button value="ADD">＋ 增加</a-radio-button>
              <a-radio-button value="SUBTRACT">− 减少</a-radio-button>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="mode !== 'KEEP'" :label="mode === 'ADD' ? '增加时长' : '减少时长'">
            <TaskWorkDurationInput v-model="magnitude" label="本次调整时长" />
          </a-form-item>
        </a-form>
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <div v-else class="work-adjustment__result" aria-live="polite">
          <span>本次工时</span>
          <strong>{{ taskWorkRuleSummary(nextRule) }}</strong>
        </div>
        <p>仅影响本次任务，不修改模板；其他办理项保持原样。</p>
      </div>
    </template>
    <template #footer>
      <a-space>
        <a-button @click="emit('cancel')">取消</a-button>
        <a-button type="primary" :disabled="!nextRule || !!error" @click="save">保存调整</a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>
<style scoped>
.work-adjustment {
  display: grid;
  gap: var(--spacing-lg);
}
.work-adjustment__baseline,
.work-adjustment__result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  border-radius: var(--border-radius, 6px);
  background: var(--color-bg-layout, #f5f6fa);
}
.work-adjustment__result {
  color: var(--brand);
  background: var(--brand-light);
}
.work-adjustment p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.work-adjustment :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
}
</style>
