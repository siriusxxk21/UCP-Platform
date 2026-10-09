<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { ApplicationDeletePreview, ApplicationRow } from '@/types/nocode/application'

const props = defineProps<{ application?: ApplicationRow }>()
const emit = defineEmits<{ cancel: []; deleted: [] }>()
const api = useNocodePlatform().applications
const preview = ref<ApplicationDeletePreview>()
const checking = ref(false),
  submitting = ref(false),
  error = ref(''),
  reason = ref('')
const allowed = computed(() => !!preview.value && !preview.value.blockers.length && !!reason.value.trim())
let request = 0
async function check() {
  const current = ++request
  const application = props.application
  preview.value = undefined
  error.value = ''
  checking.value = false
  if (!application) return
  checking.value = true
  try {
    const result = await api.deletePreview(application.id)
    if (current === request) preview.value = result
  } catch (e) {
    if (current === request) error.value = errorMessage(e)
  } finally {
    if (current === request) checking.value = false
  }
}
async function remove() {
  if (!allowed.value || checking.value || submitting.value || !preview.value) return
  submitting.value = true
  error.value = ''
  try {
    await api.deleteApplication({
      id: preview.value.application.id,
      expectedRevision: preview.value.application.revision,
      reason: reason.value.trim()
    })
    message.success('应用已移入回收站')
    emit('deleted')
  } catch (e) {
    error.value = errorMessage(e)
    // 失败后必须重新核对影响范围，不能沿用过期修订重复删除。
    preview.value = undefined
  } finally {
    submitting.value = false
  }
}
watch(
  () => props.application?.id,
  () => {
    reason.value = ''
    void check()
  },
  { immediate: true }
)
onScopeDispose(() => request++)
</script>

<template>
  <OsModalForm
    :open="!!application"
    title="删除应用"
    :width="640"
    :loading="checking || submitting"
    layout="vertical"
    :label-col="{ span: 24 }"
    :wrapper-col="{ span: 24 }"
    @cancel="!submitting && emit('cancel')"
  >
    <template #formItems>
      <div class="delete-impact" aria-live="polite">
        <a-alert
          type="warning"
          show-icon
          :message="`将“${application?.name || ''}”移入回收站`"
          description="应用将停止使用。数据中心对象及业务数据保留，其他应用不受影响。"
        />
        <a-spin v-if="checking" tip="正在检查删除影响" />
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <template v-if="preview">
          <p>涉及 {{ preview.objectCount }} 个引用对象、{{ preview.resourceCount }} 项配置资源。</p>
          <a-alert v-if="preview.blockers.length" type="error" show-icon message="当前不能删除此应用">
            <template #description>
              <ul>
                <li v-for="item in preview.blockers" :key="item">{{ item }}</li>
              </ul>
            </template>
          </a-alert>
          <p>删除后可从应用中心的回收站恢复。恢复后保持停用，需人工编辑并保存，再发布启用。</p>
        </template>
        <a-button v-if="!checking" :disabled="submitting" @click="check">重新检查</a-button>
      </div>
      <a-form-item label="删除说明" required>
        <a-textarea
          v-model:value="reason"
          aria-label="删除说明"
          placeholder="说明删除此应用的原因"
          :maxlength="1000"
          :rows="3"
          :disabled="submitting"
        />
      </a-form-item>
    </template>
    <template #footer>
      <a-space>
        <a-button :disabled="submitting" @click="emit('cancel')">取消</a-button>
        <a-button danger type="primary" :loading="submitting" :disabled="checking || !allowed" @click="remove">
          移入回收站
        </a-button>
      </a-space>
    </template>
  </OsModalForm>
</template>

<style scoped>
.delete-impact {
  display: grid;
  gap: 16px;
  margin-bottom: 20px;
}
.delete-impact p,
.delete-impact ul {
  margin: 0;
}
</style>
