<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import UserSelectorTrigger from '@/components/UserSelectorTrigger.vue'
import request from '@/utils/request'
import { useNocodePlatform } from '@/nocode/platform'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { errorMessage } from '@/nocode/data-center'
import type { DashboardResourceAction, DashboardResourcePolicy } from '@/types/nocode/report-dashboard'
const props = defineProps<{ dashboardId: string }>()
const emit = defineEmits<{ close: [] }>()
const api = useNocodePlatform().reportCenter
const policy = ref<DashboardResourcePolicy>(),
  busy = ref(false),
  error = ref(''),
  reason = ref(''),
  baseline = ref('')
const roles = ref<{ value: string; label: string }[]>([])
const snapshot = () => JSON.stringify(policy.value)
const dirty = computed(() => !!policy.value && snapshot() !== baseline.value)
useUnsavedNavigation(() => dirty.value)
let generation = 0
const actions: { value: DashboardResourceAction; label: string }[] = [
  { value: 'VIEW', label: '查看已发布看板' },
  { value: 'EXPORT', label: '导出 Excel' },
  { value: 'EDIT', label: '设计' },
  { value: 'PUBLISH', label: '发布' },
  { value: 'GRANT', label: '分配协作权限' },
  { value: 'DELETE', label: '删除' }
]
async function load() {
  const g = ++generation
  busy.value = true
  error.value = ''
  try {
    const data = await api.dashboardResourcePolicy(props.dashboardId)
    if (g !== generation) return
    policy.value = data
    baseline.value = snapshot()
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) busy.value = false
  }
}
async function loadRoles() {
  const g = generation
  try {
    const data = await request.get<{ id: string; name: string }[]>('/system/role/list-all-simple')
    if (g === generation) roles.value = data.map(r => ({ value: String(r.id), label: r.name }))
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  }
}
function resetPrincipal(member: DashboardResourcePolicy['members'][number]) {
  member.principalId = ''
  void loadRoles()
}
async function close() {
  if (!busy.value && (await confirmDiscard(dirty.value, '放弃尚未保存的看板授权修改？'))) emit('close')
}
async function save() {
  if (busy.value || !policy.value) return
  if (!reason.value.trim()) {
    error.value = '请填写授权变更原因'
    return
  }
  if (
    policy.value.members.some(
      m => !m.principalId || !m.actions.length || (m.actions.includes('EXPORT') && !m.actions.includes('VIEW'))
    )
  ) {
    error.value = '请选择成员和操作权限；导出须同时允许查看看板'
    return
  }
  const g = generation
  busy.value = true
  error.value = ''
  try {
    const data = await api.saveDashboardResourcePolicy({
      id: props.dashboardId,
      expectedRevision: policy.value.revision,
      members: policy.value.members,
      reason: reason.value.trim()
    })
    if (g !== generation) return
    policy.value = data
    baseline.value = snapshot()
    reason.value = ''
    message.success('看板协作权限已保存并立即生效')
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) busy.value = false
  }
}
void load()
onScopeDispose(() => generation++)
</script>
<template>
  <OsModalForm
    :open="true"
    title="看板协作权限"
    display-mode="drawer"
    :width="720"
    :allow-switch-display="false"
    :resizable="false"
    :loading="busy"
    :disabled="!policy"
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
        message="查看者可使用已发布图表；业务数据仍按对象授权上限与成员数据权限求交。导出还须具备相同数据的导出权限。"
      />
      <a-skeleton v-if="busy && !policy" active />
      <div v-if="policy" class="dialog-dashboard-members">
        <a-button
          :disabled="busy || policy.members.length >= 200"
          @click="policy.members.push({ principalKind: 'USER', principalId: '', actions: ['VIEW'] })"
        >
          添加协作成员
        </a-button>
        <a-card v-for="(member, index) in policy.members" :key="index" size="small">
          <a-space wrap>
            <a-select
              v-model:value="member.principalKind"
              aria-label="成员类型"
              :disabled="busy"
              :options="[
                { value: 'USER', label: '系统用户' },
                { value: 'ROLE', label: '系统角色' }
              ]"
              @change="resetPrincipal(member)"
            />
            <UserSelectorTrigger
              v-if="member.principalKind === 'USER'"
              selector-type="user"
              mode="single"
              :disabled="busy"
              :model-value="member.principalId ? [member.principalId] : []"
              @update:model-value="ids => (member.principalId = String(ids[0] || ''))"
            />
            <a-select
              v-else
              v-model:value="member.principalId"
              class="dialog-dashboard-principal"
              aria-label="成员角色"
              show-search
              option-filter-prop="label"
              :disabled="busy"
              :options="roles"
              @focus="loadRoles"
            />
            <a-button type="link" danger :disabled="busy" @click="policy.members.splice(index, 1)">移除成员</a-button>
          </a-space>
          <a-checkbox-group
            v-model:value="member.actions"
            :options="actions"
            :disabled="busy"
            class="dialog-dashboard-actions"
          />
        </a-card>
        <a-form layout="vertical">
          <a-form-item label="变更原因" required>
            <a-textarea v-model:value="reason" aria-label="授权变更原因" :maxlength="1000" :rows="2" :disabled="busy" />
          </a-form-item>
        </a-form>
      </div>
      <a-button v-if="!policy && !busy" @click="load">重新加载</a-button>
    </template>
  </OsModalForm>
</template>
<style scoped>
.dialog-dashboard-members {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  margin-top: var(--spacing-lg);
}
.dialog-dashboard-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
}
.dialog-dashboard-principal {
  min-width: calc(var(--spacing-lg) * 12);
}
</style>
