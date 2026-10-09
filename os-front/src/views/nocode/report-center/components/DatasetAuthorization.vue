<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import UserSelectorTrigger from '@/components/UserSelectorTrigger.vue'
import request from '@/utils/request'
import { useNocodePlatform } from '@/nocode/platform'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { errorMessage } from '@/nocode/data-center'
import type { DatasetResourcePolicy } from '@/types/nocode/report-center'
const props = defineProps<{ datasetId: string }>()
const emit = defineEmits<{ close: [] }>()
const api = useNocodePlatform().reportCenter
const resource = ref<DatasetResourcePolicy>()
const busy = ref(false),
  ready = ref(false),
  error = ref(''),
  reason = ref(''),
  baseline = ref('')
const roles = ref<{ value: string; label: string }[]>([])
let disposed = false
const snapshot = () => JSON.stringify(resource.value)
const dirty = computed(() => ready.value && snapshot() !== baseline.value)
useUnsavedNavigation(() => dirty.value)
const resourceActions = [
  { label: '查看配置', value: 'VIEW_META' },
  { label: '编辑', value: 'EDIT' },
  { label: '发布', value: 'PUBLISH' },
  { label: '使用', value: 'USE' },
  { label: '分配协作权限', value: 'GRANT' },
  { label: '删除', value: 'DELETE' }
]
async function close() {
  if (!busy.value && (await confirmDiscard(dirty.value, '放弃尚未保存的授权修改？'))) emit('close')
}
async function load() {
  busy.value = true
  try {
    resource.value = await api.resourcePolicy(props.datasetId)
    if (!disposed) {
      baseline.value = snapshot()
      ready.value = true
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function loadRoles() {
  try {
    roles.value = (await request.get<{ id: string; name: string }[]>('/system/role/list-all-simple')).map(r => ({
      value: String(r.id),
      label: r.name
    }))
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function save() {
  if (busy.value || !ready.value || !resource.value) return
  if (!reason.value.trim()) {
    error.value = '请填写授权变更原因'
    return
  }
  busy.value = true
  error.value = ''
  try {
    resource.value = await api.saveResourcePolicy({
      datasetId: props.datasetId,
      reason: reason.value.trim(),
      expectedRevision: resource.value.revision,
      members: resource.value.members
    })
    baseline.value = snapshot()
    reason.value = ''
    message.success('协作权限已保存并立即生效')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
void load()
onScopeDispose(() => {
  disposed = true
})
</script>
<template>
  <OsModalForm
    :open="true"
    title="资源协作权限"
    :width="920"
    :loading="busy"
    :disabled="!ready"
    :wrap-form="false"
    ok-text="保存授权"
    @ok="save"
    @cancel="close"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-alert
        type="info"
        show-icon
        message="协作权限控制数据集的配置和使用。数据集默认可读取当前租户下的所有可用数据对象，无需另配对象权限。"
      />
      <div v-if="ready && resource" class="authorization-body">
        <a-button
          :disabled="busy || resource.members.length >= 200"
          @click="resource.members.push({ principalKind: 'USER', principalId: '', actions: ['VIEW_META'] })"
        >
          添加协作成员
        </a-button>
        <a-card v-for="(member, index) in resource.members" :key="index" size="small" class="member-card">
          <a-space wrap>
            <a-select
              v-model:value="member.principalKind"
              aria-label="成员类型"
              :disabled="busy"
              :options="[
                { label: '系统用户', value: 'USER' },
                { label: '系统角色', value: 'ROLE' }
              ]"
              @change="member.principalId = ''"
              @focus="loadRoles"
            />
            <UserSelectorTrigger
              v-if="member.principalKind === 'USER'"
              selector-type="user"
              mode="single"
              :disabled="busy"
              :model-value="member.principalId ? [member.principalId] : []"
              @update:model-value="
                ids => {
                  member.principalId = String(ids[0] || '')
                }
              "
            />
            <a-select
              v-else
              v-model:value="member.principalId"
              aria-label="成员角色"
              class="principal-select"
              :disabled="busy"
              :options="roles"
              show-search
              option-filter-prop="label"
            />
            <a-button type="link" danger :disabled="busy" @click="resource.members.splice(index, 1)">移除成员</a-button>
          </a-space>
          <a-checkbox-group
            v-model:value="member.actions"
            :disabled="busy"
            :options="resourceActions"
            class="member-actions"
          />
        </a-card>
        <a-form-item label="授权变更原因" required class="member-actions">
          <a-textarea v-model:value="reason" aria-label="授权变更原因" :disabled="busy" :maxlength="1000" :rows="2" />
        </a-form-item>
      </div>
    </template>
  </OsModalForm>
</template>
<style scoped>
.authorization-body {
  margin-top: 16px;
}
.member-card {
  margin-top: 12px;
}
.member-actions {
  margin-top: 16px;
}
.principal-select {
  min-width: 200px;
}
</style>
