<script lang="ts" setup>
/**
 * 应用统一图标组件。
 *
 * 支持 Ant Design 图标名称和 iconfont: 前缀的 Iconfont Symbol ID；
 * 无法识别图标时渲染默认插槽，由调用方决定降级内容。
 */
import { computed } from 'vue'
import { getIcon, getIconfontType, IconFont } from '@/utils/icons'

defineOptions({
  name: 'AppIcon',
  inheritAttrs: false
})

const props = defineProps<{
  name?: string
}>()

const iconfontType = computed(() => getIconfontType(props.name))
const antDesignIcon = computed(() => getIcon(props.name))
</script>

<template>
  <IconFont v-if="iconfontType" :type="iconfontType" v-bind="$attrs" />
  <component :is="antDesignIcon" v-else-if="antDesignIcon" v-bind="$attrs" />
  <slot v-else />
</template>
