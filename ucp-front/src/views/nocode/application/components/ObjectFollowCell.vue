<script setup lang="ts">
import { computed } from 'vue'
import type { ObjectFollow } from '@/types/nocode/application'
import {
  followDisplay,
  followSuspendedHint as suspendedHint,
  followSwitchHint
} from '@/nocode/application-object-follow'

/** 「已引用对象」表里一行的自动跟随：开关 + 状态 + 立即跟随 / 重试。只发事件，不自己调接口。 */
const props = defineProps<{
  /** 没有状态（对象还没保存进应用）时不显示任何东西。 */
  follow?: ObjectFollow
  /** 没有应用编辑权限或工作台正忙：开关只读。 */
  readonly?: boolean
  /** 不能操作的原因（工作台有未保存修改）；有值时三个操作都禁用并悬停提示。 */
  blockedReason?: string
  loading?: boolean
  /** 应用不是启用状态：自动跟随不处理它，「立即跟随 / 重试」不显示（改用同一行的「同步最新版本」）。 */
  suspended?: boolean
}>()
const emit = defineEmits<{ toggle: [enabled: boolean]; run: [] }>()
const display = computed(() => (props.follow ? followDisplay(props.follow) : undefined))
const disabled = computed(() => !!props.readonly || !!props.blockedReason || !!props.loading)
</script>

<template>
  <span v-if="follow" class="object-follow">
    <a-tooltip :title="blockedReason || (follow.enabled ? followSwitchHint : undefined)">
      <span>
        <a-switch
          size="small"
          aria-label="自动跟随"
          :checked="follow.enabled"
          :loading="loading"
          :disabled="disabled"
          @change="emit('toggle', $event === true)"
        />
      </span>
    </a-tooltip>
    <template v-if="suspended && (display === 'BEHIND' || display === 'PENDING')">
      <a-tooltip :title="suspendedHint">
        <a-tag>应用停用中，不自动跟随</a-tag>
      </a-tooltip>
    </template>
    <template v-else-if="display === 'BEHIND'">
      <a-tag>待跟随</a-tag>
      <a-tooltip :title="blockedReason || undefined">
        <span>
          <a-button type="link" size="small" :disabled="disabled" @click="emit('run')">立即跟随</a-button>
        </span>
      </a-tooltip>
    </template>
    <template v-else-if="display === 'PENDING'">
      <a-tooltip :title="follow.pendingReason || undefined">
        <a-tag color="orange">跟随待处理</a-tag>
      </a-tooltip>
      <a-tooltip :title="blockedReason || undefined">
        <span>
          <a-button type="link" size="small" :disabled="disabled" @click="emit('run')">重试</a-button>
        </span>
      </a-tooltip>
    </template>
  </span>
</template>

<style scoped>
.object-follow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.object-follow .ant-tag {
  margin-inline-end: 0;
}
</style>
