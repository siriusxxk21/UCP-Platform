<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DownOutlined, RightOutlined } from '@ant-design/icons-vue'
import UserSelectorTrigger from '@/components/UserSelectorTrigger.vue'
import { getUsersByIds } from '@/api/system/user'
import request from '@/utils/request'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { PublishedObject } from '@/types/nocode/application'
import {
  BusinessAction,
  PrincipalKind,
  RecordScope,
  type ApplicationPolicy,
  type ApplicationMember,
  type ObjectSharingGrant
} from '@/types/nocode/authorization'
import ObjectGrantFields from '../../components/ObjectGrantFields.vue'
import { countTightened, defaultObjectGrant, restrictObjectGrant } from '@/nocode/object-sharing'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { memberProblems, memberSummary, principalLabel } from '@/nocode/member-summary'
const props = defineProps<{
  applicationId: string
  objects: Record<string, PublishedObject>
}>()
const api = useNocodePlatform().applications
const emit = defineEmits<{ 'focus-editor': [] }>()
const toolbar = ref<HTMLElement>()
async function focusEditor() {
  emit('focus-editor')
  await nextTick()
  toolbar.value?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  toolbar.value?.querySelector<HTMLElement>('[data-authorization-save]')?.focus({ preventScroll: true })
}
const policy = ref<ApplicationPolicy>({ revision: 0, members: [] }),
  busy = ref(false),
  error = ref(''),
  dirty = ref(false)
const ceilings = ref<ObjectSharingGrant[]>([])
/** 最近一次保存时服务端按上一层收紧掉的项数；只作提示，不算用户编辑。 */
const tightened = ref(0)
const ceiling = (id: string) => {
  const shared = ceilings.value.find(g => g.objectId === id)?.permission || null
  return shared
}
const canClose = async () => {
  const close = await confirmDiscard(dirty.value, '成员与权限尚未保存，是否放弃本次授权修改？')
  if (!close) await focusEditor()
  return close
}
defineExpose({ canClose })
useUnsavedNavigation(() => dirty.value, {
  title: '成员与权限尚未保存，是否放弃本次授权修改？',
  continueEditing: () => {
    void focusEditor()
  }
})
const roles = ref<Array<{ label: string; value: string }>>([])
/* ── 卡片折叠（业务方 2026-10-04：默认折叠，展开编辑；新加的默认展开；有问题的自动展开并定位） ── */
const expanded = ref(new Set<ApplicationMember>())
const isOpen = (member: ApplicationMember) => expanded.value.has(member)
function toggle(member: ApplicationMember) {
  if (expanded.value.has(member)) expanded.value.delete(member)
  else expanded.value.add(member)
}
/** 用户名称：折叠时标题行要显示「是谁」；读取失败只影响标题，显示用户 ID。 */
const userNames = ref<Record<string, string>>({})
async function loadUserNames(ids: string[]) {
  const missing = [...new Set(ids.filter(id => id && !userNames.value[id]))]
  if (!missing.length) return
  try {
    const users = await getUsersByIds(missing)
    userNames.value = {
      ...userNames.value,
      ...Object.fromEntries(users.map(u => [String(u.id), u.nickname || u.username || String(u.id)]))
    }
  } catch {
    // 名称只用于折叠时的标题行，读取失败时标题显示用户 ID，不打断授权编辑。
  }
}
const objectName = (id: string) => props.objects[id]?.definition.objectName || '对象已移出草稿'
const summary = (member: ApplicationMember) =>
  memberSummary(member, objectName, principalLabel(member, userNames.value, roles.value))
const problems = ref<Array<{ index: number; message: string }>>([])
const problemAt = (index: number) => problems.value.find(p => p.index === index)?.message || ''
async function revealProblems() {
  for (const p of problems.value) {
    const member = policy.value.members[p.index]
    if (member) expanded.value.add(member)
  }
  await nextTick()
  const first = problems.value[0]
  if (first)
    toolbar.value?.parentElement
      ?.querySelector<HTMLElement>(`[data-member-index="${first.index}"]`)
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
async function load() {
  busy.value = true
  error.value = ''
  try {
    const [members, shares] = await Promise.all([
      api.authorization(props.applicationId),
      api.sharing(props.applicationId)
    ])
    policy.value = members
    ceilings.value = shares
    for (const member of policy.value.members)
      for (const grant of member.objects) {
        grant.readRelations ||= []
        grant.writeRelations ||= []
      }
    roles.value = (await request.get<Array<{ id: string; name: string }>>('/system/role/list-all-simple')).map(r => ({
      label: r.name,
      value: String(r.id)
    }))
    expanded.value = new Set()
    problems.value = []
    void loadUserNames(
      policy.value.members.filter(m => m.principalKind === PrincipalKind.USER).map(m => String(m.principalId))
    )
    dirty.value = false
    tightened.value = 0
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function save() {
  error.value = ''
  problems.value = memberProblems(policy.value.members)
  if (problems.value.length) {
    error.value = problems.value.map(p => p.message).join('；')
    await revealProblems()
    return
  }
  busy.value = true
  // 保存后服务端返回新对象：按位置保留已展开的卡片，保存不打断正在编辑的卡片。
  const open = policy.value.members.map(isOpen)
  try {
    const submitted: ApplicationMember[] = JSON.parse(JSON.stringify(policy.value.members))
    policy.value = await api.saveAuthorization({
      applicationId: props.applicationId,
      expectedRevision: policy.value.revision,
      members: policy.value.members
    })
    expanded.value = new Set(policy.value.members.filter((_, index) => open[index]))
    dirty.value = false
    tightened.value = countTightened(submitted, policy.value.members)
    message.success('应用授权已生效')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function add() {
  policy.value.members.push({ principalKind: PrincipalKind.USER, principalId: '', objects: [] })
  const added = policy.value.members.at(-1)
  if (added) expanded.value.add(added)
  dirty.value = true
}
function resetPrincipal(member: ApplicationMember) {
  member.principalId = ''
  dirty.value = true
}
function removeMember(index: number) {
  policy.value.members.splice(index, 1)
  problems.value = []
  dirty.value = true
}
function chooseObjects(member: ApplicationMember, ids: string[]) {
  member.objects = ids.map(
    id => member.objects.find(g => g.objectId === id) || defaultObjectGrant(id, [BusinessAction.READ], RecordScope.OWN)
  )
  dirty.value = true
}
function tighten(member: ApplicationMember, index: number) {
  const grant = member.objects[index]!
  const upper = ceiling(grant.objectId)
  if (upper) member.objects[index] = restrictObjectGrant(grant, upper)
  else member.objects.splice(index, 1)
  dirty.value = true
}
watch(() => props.applicationId, load, { immediate: true })
</script>
<template>
  <div ref="toolbar" class="member-toolbar">
    <div>
      <strong>成员与数据权限</strong>
      <span v-if="dirty" class="muted">· 有未保存授权</span>
    </div>
    <a-space>
      <a-button :disabled="busy" @click="load">重新加载</a-button>
      <a-button :disabled="busy" @click="add">添加成员或角色</a-button>
      <a-button data-authorization-save type="primary" :loading="busy" @click="save">保存授权</a-button>
    </a-space>
  </div>
  <a-alert
    message="成员只能使用应用已获得的数据权限。应用权限减少后，成员超出范围的权限立即失效；点击“按应用权限更新”可移除这些已失效配置，其余权限保持不变。"
    type="info"
    show-icon
    class="notice"
  />
  <a-alert v-if="error" :message="error" type="error" show-icon class="notice" />
  <a-alert v-if="tightened" :message="`已按上一层收紧 ${tightened} 项`" type="info" show-icon class="notice" />
  <a-empty v-if="!policy.members.length" description="尚未授权其他成员" />
  <a-card
    v-for="(member, index) in policy.members"
    :key="index"
    size="small"
    class="member-card"
    :class="{ 'member-card--open': isOpen(member), 'member-card--problem': !!problemAt(index) }"
    :data-member-index="index"
  >
    <button
      type="button"
      class="member-summary"
      :aria-expanded="isOpen(member)"
      :aria-label="`${isOpen(member) ? '收起' : '展开编辑'}：${summary(member).principal}`"
      @click="toggle(member)"
    >
      <component :is="isOpen(member) ? DownOutlined : RightOutlined" class="member-summary-icon" />
      <span class="member-summary-body">
        <span class="member-summary-title">
          <strong>{{ summary(member).principal }}</strong>
          <span class="muted">{{ summary(member).objects || '未选择可访问对象' }}</span>
        </span>
        <span v-for="g in summary(member).grants" :key="g.objectId" class="member-summary-grant">
          <b>{{ g.objectName }}</b>
          <span>{{ g.scope }}</span>
          <span class="muted">{{ g.actions }}</span>
        </span>
      </span>
      <span class="member-summary-toggle">{{ isOpen(member) ? '收起' : '展开编辑' }}</span>
    </button>
    <p v-if="problemAt(index)" class="danger member-problem" role="alert">{{ problemAt(index) }}</p>
    <div v-if="isOpen(member)" class="member-editor">
      <div class="member-toolbar">
        <a-space>
          <a-select
            v-model:value="member.principalKind"
            :disabled="busy"
            :options="[
              { label: '系统用户', value: PrincipalKind.USER },
              { label: '系统角色', value: PrincipalKind.ROLE }
            ]"
            style="width: 120px"
            @change="resetPrincipal(member)"
          />
          <UserSelectorTrigger
            v-if="member.principalKind === PrincipalKind.USER"
            selector-type="user"
            :disabled="busy"
            mode="single"
            :model-value="member.principalId ? [member.principalId] : []"
            @update:model-value="
              ids => {
                member.principalId = String(ids[0] || '')
                dirty = true
              }
            "
            @change="
              (items: Array<{ id: string | number; name: string }>) =>
                (userNames = { ...userNames, ...Object.fromEntries(items.map(i => [String(i.id), i.name])) })
            "
          />
          <a-select
            v-else
            v-model:value="member.principalId"
            :disabled="busy"
            :options="roles"
            show-search
            option-filter-prop="label"
            placeholder="选择系统角色"
            style="width: 240px"
            @change="dirty = true"
          />
        </a-space>
        <a-popconfirm title="移除此成员授权？保存后生效。" :disabled="busy" @confirm="removeMember(index)">
          <a-button type="link" danger :disabled="busy">移除</a-button>
        </a-popconfirm>
      </div>
      <div>
        <a-form-item label="可访问对象">
          <a-select
            mode="multiple"
            :disabled="busy"
            show-search
            option-filter-prop="label"
            :value="member.objects.map(g => g.objectId)"
            :options="
              Object.values(objects).map(o => ({
                label: o.definition.objectName,
                value: o.objectId,
                disabled: !ceiling(o.objectId)
              }))
            "
            @change="(value: unknown) => chooseObjects(member, value as string[])"
          />
        </a-form-item>
        <section v-for="(g, grantIndex) in member.objects" :key="g.objectId" class="grant">
          <h4>{{ objects[g.objectId]?.definition.objectName || '对象已移出草稿' }}</h4>
          <a-button size="small" :disabled="busy" @click="tighten(member, grantIndex)">按应用权限更新</a-button>
          <ObjectGrantFields
            v-if="objects[g.objectId]"
            :model-value="g"
            :definition="objects[g.objectId]!.definition"
            :ceiling="ceiling(g.objectId)"
            upper-name="应用数据权限"
            :readonly="busy"
            @update:model-value="value => (member.objects[grantIndex] = value)"
            @change="dirty = true"
          />
        </section>
      </div>
    </div>
  </a-card>
</template>
<style scoped>
.member-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 16px;
}
.notice,
.member-card {
  margin-bottom: 16px;
}
.grant {
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
}
.member-summary {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 2px 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
}
.member-summary:focus-visible {
  outline: 2px solid var(--ant-color-primary, #1677ff);
  outline-offset: 2px;
  border-radius: 4px;
}
.member-summary-icon {
  margin-top: 4px;
  color: #8c8c8c;
}
.member-summary-body {
  display: grid;
  gap: 4px;
  flex: 1;
  min-width: 0;
}
.member-summary-title {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: baseline;
}
.member-summary-grant {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 12px;
}
.member-summary-toggle {
  flex: none;
  color: var(--ant-color-primary, #1677ff);
  font-size: 12px;
  margin-top: 2px;
}
.member-card--problem {
  border-color: #ff7875;
}
.member-problem {
  margin: 8px 0 0;
}
.member-editor {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #f0f0f0;
}
.grant-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.muted {
  color: #8c8c8c;
}
.danger {
  color: #cf1322;
}
@media (max-width: 800px) {
  .grant-columns {
    grid-template-columns: 1fr;
  }
}
</style>
