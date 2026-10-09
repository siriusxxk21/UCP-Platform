<template>
  <a-select
    :value="modelValue"
    :placeholder="placeholder"
    allow-clear
    :filter-option="filterOption"
    :list-height="320"
    show-search
    style="width: 100%"
    virtual
    @change="handleChange"
  >
    <a-select-opt-group v-if="iconfontIconList.length" label="项目 Iconfont">
      <a-select-option
        v-for="item in iconfontIconList"
        :key="item.value"
        :label="`${item.label} ${item.name}`"
        :value="item.value"
      >
        <span class="icon-option">
          <AppIcon :name="item.value" class="icon-option__preview" />
          <span>{{ item.label }}</span>
          <span class="icon-option__code">{{ item.name }}</span>
        </span>
      </a-select-option>
    </a-select-opt-group>
    <a-select-opt-group label="Ant Design Icons">
      <a-select-option v-for="item in antDesignIconList" :key="item.value" :label="item.label" :value="item.value">
        <span class="icon-option">
          <AppIcon :name="item.value" class="icon-option__preview" />
          <span>{{ item.label }}</span>
        </span>
      </a-select-option>
    </a-select-opt-group>
  </a-select>
</template>

<script lang="ts" setup>
/** 菜单图标选择器，统一提供项目 Iconfont 和全量 Ant Design Icons。 */
import AppIcon from '@/components/AppIcon.vue'
import { antDesignIconList, iconfontIconList } from '@/utils/icons'

defineProps<{
  modelValue?: string
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | undefined]
}>()

function handleChange(value: string | undefined) {
  emit('update:modelValue', value)
}

function filterOption(input: string, option: { label?: string }) {
  return String(option?.label || '')
    .toLowerCase()
    .includes(input.trim().toLowerCase())
}
</script>

<style scoped>
.icon-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.icon-option__preview {
  flex: 0 0 auto;
  font-size: 16px;
}

.icon-option__code {
  color: #8c8c8c;
  font-size: 12px;
}
</style>