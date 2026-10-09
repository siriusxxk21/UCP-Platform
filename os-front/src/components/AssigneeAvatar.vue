<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  /** 用户名称（取首字符作为头像文字） */
  name: string
  /** 头像背景色（十六进制颜色值），不传则根据 name 自动哈希 */
  color?: string
  /** 头像图片地址，有值时优先展示图片 */
  src?: string
  /** 是否显示名称文字（默认 true） */
  showName?: boolean
  /** 头像大小（默认 22px） */
  size?: number
  /** 名称字体大小（默认 inherit） */
  nameFontSize?: string
}

const props = withDefaults(defineProps<Props>(), {
  color: '',
  src: '',
  showName: true,
  size: 22,
  nameFontSize: ''
})

const AVATAR_COLORS = [
  '#7C3AED',
  '#2563EB',
  '#059669',
  '#D97706',
  '#DC2626',
  '#0891B2',
  '#4F46E5',
  '#0D9488',
  '#C2410C',
  '#B91C1C',
  '#4338CA',
  '#15803D',
  '#B45309',
  '#BE185D',
  '#0E7490',
  '#6D28D9',
  '#1D4ED8'
]

function hashColor(id: string): string {
  let hash = 0
  for (let i = 0; i < id.length; i++) {
    hash = (hash << 5) - hash + id.charCodeAt(i)
    hash |= 0
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]
}

const initial = computed(() => {
  if (!props.name) return '—'
  return props.name[0].toUpperCase()
})

const bgColor = computed(() => props.color || hashColor(props.name || ''))

const avatarStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.max(10, Math.round(props.size * 0.5))}px`,
  background: bgColor.value
}))

const nameStyle = computed(() => {
  const styles: Record<string, string> = {}
  if (props.nameFontSize) styles['font-size'] = props.nameFontSize
  return styles
})
</script>

<template>
  <div class="assignee-cell">
    <div class="assignee-avatar" :style="avatarStyle">
      <img v-if="src" :alt="name || 'avatar'" :src="src" />
      <template v-else>
        {{ initial }}
      </template>
    </div>
    <span v-if="showName" class="assignee-name" :style="nameStyle">{{ name || '未指派' }}</span>
  </div>
</template>

<style scoped>
.assignee-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.assignee-avatar {
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
  flex-shrink: 0;
  line-height: 1.2;
  text-align: center;
  user-select: none;
  overflow: hidden;
}

.assignee-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.assignee-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
