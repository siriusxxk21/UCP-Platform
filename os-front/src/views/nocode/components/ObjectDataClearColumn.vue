<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { objectDataColumnChoices, objectDataColumnRequest } from '@/nocode/object-data'
import type {
  ObjectDataClearColumn,
  ObjectDataClearColumnPreview,
  ObjectDataClearColumnResult,
  ObjectDataModel
} from '@/types/nocode/object-data'

const props = defineProps<{
  objectId: string
  model: ObjectDataModel
  initialFieldId?: string
}>()
const emit = defineEmits<{ cancel: []; cleared: [result: ObjectDataClearColumnResult] }>()
const platform = useNocodePlatform(),
  api = platform.objectData
const model = shallowRef(props.model),
  fieldId = ref(props.initialFieldId || ''),
  preview = ref<ObjectDataClearColumnPreview>(),
  request = ref<ObjectDataClearColumn>(),
  checking = ref(false),
  clearing = ref(false),
  failure = ref('')
const choices = computed(() => objectDataColumnChoices(model.value))
const affected = computed(() => (preview.value?.activeRows || 0) + (preview.value?.deletedRows || 0))
const authorized = computed(
  () => platform.hasPermission('nocode:object:query') && platform.hasPermission('nocode:object:manage')
)
const canClear = computed(
  () =>
    authorized.value &&
    !checking.value &&
    !clearing.value &&
    !!request.value &&
    preview.value?.allowed === true &&
    !preview.value.blockers.length &&
    !!preview.value.impactToken &&
    affected.value > 0
)
const blockers = computed(() => [...new Set(preview.value?.blockers || [])].filter(item => item.trim().length > 0))
let generation = 0
async function inspect() {
  if (clearing.value) return
  const current = ++generation
  preview.value = undefined
  request.value = undefined
  failure.value = ''
  checking.value = false
  if (!fieldId.value || !authorized.value) return
  checking.value = true
  const selected = fieldId.value
  try {
    // 每次检查读取当前已发布结构；执行仍使用此次检查的版本与凭据，不自动套用新影响。
    const fresh = await api.model(props.objectId)
    if (current !== generation) return
    model.value = fresh
    const body = objectDataColumnRequest(props.objectId, fresh, selected)
    const result = await api.clearColumnPreview(body)
    if (current !== generation) return
    request.value = body
    preview.value = result
  } catch (cause) {
    if (current === generation) failure.value = errorMessage(cause)
  } finally {
    if (current === generation) checking.value = false
  }
}
watch(fieldId, () => void inspect(), { immediate: true })
async function clear() {
  if (!canClear.value || !request.value || !preview.value?.impactToken) return
  clearing.value = true
  failure.value = ''
  try {
    const result = await api.clearColumn({ ...request.value, impactToken: preview.value.impactToken })
    message.success(`已清空此列 ${result.clearedActiveRows + result.clearedDeletedRows} 个值，记录与其他列保留`)
    emit('cleared', result)
  } catch (cause) {
    // 请求结果不确定或影响变化时都不能沿用旧确认；保留列选择，由用户重新检查实际情况。
    preview.value = undefined
    request.value = undefined
    const detail = errorMessage(cause)
    failure.value = detail.includes('重新检查') ? detail : `${detail}；请重新检查后再确认。`
  } finally {
    clearing.value = false
  }
}
function cancel() {
  if (!clearing.value) emit('cancel')
}
onBeforeUnmount(() => generation++)
</script>

<template>
  <OsModalForm
    :open="true"
    title="清空整列"
    :width="740"
    display-mode="modal"
    :allow-switch-display="false"
    :loading="clearing"
    @cancel="cancel"
  >
    <template #formItems>
      <a-form-item label="选择数据列" :label-col="{ span: 24 }" :wrapper-col="{ span: 24 }">
        <a-select
          v-model:value="fieldId"
          aria-label="选择要清空的数据列"
          placeholder="选择主表或明细中的一列"
          show-search
          option-filter-prop="label"
          :options="choices"
          :disabled="clearing"
        />
      </a-form-item>
      <a-alert v-if="failure" type="error" show-icon :message="failure" />
      <a-spin v-if="checking" tip="正在检查已发布列、历史值和依赖" />
      <template v-if="preview">
        <a-alert
          :type="!preview.allowed || blockers.length ? 'error' : affected ? 'warning' : 'info'"
          show-icon
          :message="
            !preview.allowed || blockers.length
              ? '当前不能清空此列'
              : affected
                ? `将清空此列全部 ${affected} 个值`
                : '本列没有需要清空的值'
          "
          :description="!preview.allowed && !blockers.length ? preview.message : undefined"
        />
        <ul v-if="blockers.length" class="clear-column-blockers">
          <li v-for="reason in blockers" :key="reason">{{ reason }}</li>
        </ul>
        <a-descriptions class="clear-column-facts" size="small" bordered :column="2">
          <a-descriptions-item label="数据列" :span="2">
            {{ preview.objectName }} · {{ preview.detailName || '主表' }} · {{ preview.fieldName }}
          </a-descriptions-item>
          <a-descriptions-item label="数据库实际类型">{{ preview.columnType || '物理列未找到' }}</a-descriptions-item>
          <a-descriptions-item label="已发布版本">V{{ preview.versionNo }}</a-descriptions-item>
          <a-descriptions-item label="正常记录中有值">{{ preview.activeRows }} 条</a-descriptions-item>
          <a-descriptions-item label="逻辑删除记录中有值">{{ preview.deletedRows }} 条</a-descriptions-item>
        </a-descriptions>
      </template>
      <div class="clear-column-scope">
        <p>立即清空已发布数据中的这一整列，记录和其他列保留；已有历史记录保留。</p>
        <p>范围包含正常记录和逻辑删除记录，当前筛选、分页或定位记录不会缩小范围。引用此对象的应用共享此次修改。</p>
      </div>
    </template>
    <template #footer>
      <a-space>
        <a-button :disabled="clearing" @click="cancel">取消</a-button>
        <a-button :disabled="!fieldId || clearing" :loading="checking" @click="inspect">重新检查</a-button>
        <a-button type="primary" danger :disabled="!canClear" :loading="clearing" @click="clear">
          {{ preview && affected ? `清空此列 ${affected} 个值` : '清空此列' }}
        </a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>

<style scoped>
.clear-column-facts {
  margin-top: 16px;
}
.clear-column-blockers {
  margin-block: 12px;
  padding-left: 20px;
  line-height: 1.7;
}
.clear-column-scope {
  margin-top: 16px;
  color: var(--text-secondary);
  font-size: var(--ant-font-size-sm, 12px);
  line-height: 1.7;
}
.clear-column-scope p {
  margin-bottom: 4px;
}
</style>
