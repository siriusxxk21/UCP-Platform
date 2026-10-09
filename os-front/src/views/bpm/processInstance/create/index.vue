<script lang="ts" setup>
import type { BpmCategoryApi } from '@/api/bpm/category'
import { getCategorySimpleList } from '@/api/bpm/category'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { getProcessDefinitionList } from '@/api/bpm/definition'
import { SearchOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useCompactViewport } from '@/composables/useCompactViewport'
import { getProcessInstance } from '@/api/bpm/processInstance'
import ProcessDefinitionForm from './modules/form.vue'

defineOptions({ name: 'BpmProcessInstanceCreate' })

const route = useRoute()
const compactViewport = useCompactViewport()
const loading = ref(false)
const categoryList = ref<BpmCategoryApi.Category[]>([])
const activeCategory = ref('')
const searchName = ref('')
const processDefinitionList = ref<BpmProcessDefinitionApi.ProcessDefinition[]>([])
const selectedProcessDefinition = ref<BpmProcessDefinitionApi.ProcessDefinition>()
const processDefinitionFormRef = ref<InstanceType<typeof ProcessDefinitionForm>>()

function normalizeList<T>(payload: T[] | { list?: T[]; records?: T[] } | null | undefined): T[] {
  if (Array.isArray(payload)) return payload
  return payload?.list || payload?.records || []
}

const filteredProcessDefinitionList = computed(() => {
  const keyword = searchName.value.trim().toLowerCase()
  if (!keyword) return processDefinitionList.value
  return processDefinitionList.value.filter(item => item.name?.toLowerCase().includes(keyword))
})

const processDefinitionGroup = computed(() => {
  const grouped: Record<string, BpmProcessDefinitionApi.ProcessDefinition[]> = {}
  filteredProcessDefinitionList.value.forEach(item => {
    const category = item.category || ''
    if (!grouped[category]) grouped[category] = []
    grouped[category].push(item)
  })
  return grouped
})

const availableCategories = computed(() => {
  const codes = Object.keys(processDefinitionGroup.value)
  return categoryList.value.filter(item => codes.includes(item.code))
})

async function loadList() {
  loading.value = true
  try {
    const [categories, definitions] = await Promise.all([
      getCategorySimpleList(),
      getProcessDefinitionList({ suspensionState: 1 })
    ])
    categoryList.value = normalizeList(categories)
    processDefinitionList.value = normalizeList(definitions)
    await tryInitRestartProcess()
  } catch (error: any) {
    console.error('加载可发起流程失败:', error)
    message.error(error.message || '加载可发起流程失败')
  } finally {
    loading.value = false
  }
}

async function tryInitRestartProcess() {
  const processInstanceId = route.query.processInstanceId
  if (!processInstanceId) return
  const processInstance = await getProcessInstance(processInstanceId as any)
  if (!processInstance) {
    message.error('重新发起流程失败，原因：流程实例不存在')
    return
  }
  const definition = processDefinitionList.value.find(
    item => item.key === processInstance.processDefinition?.key || item.id === processInstance.processDefinitionId
  )
  if (!definition) {
    message.error('重新发起流程失败，原因：流程定义不存在')
    return
  }
  await handleSelect(definition, processInstance.formVariables)
}

async function handleSelect(
  definition: BpmProcessDefinitionApi.ProcessDefinition,
  formVariables?: Record<string, any>
) {
  selectedProcessDefinition.value = definition
  await nextTick()
  processDefinitionFormRef.value?.initProcessInfo(definition, formVariables)
}

function handleBack() {
  selectedProcessDefinition.value = undefined
}

watch(
  availableCategories,
  categories => {
    if (!categories.length) {
      activeCategory.value = ''
      return
    }
    if (!categories.some(item => item.code === activeCategory.value)) activeCategory.value = categories[0].code
  },
  { immediate: true }
)

onMounted(() => {
  loadList()
})
</script>

<template>
  <div class="bpm-process-create-page">
    <template v-if="!selectedProcessDefinition">
      <a-card :bordered="false" :loading="loading">
        <template #title>
          <div class="page-title">发起流程</div>
        </template>
        <template #extra>
          <a-input v-model:value="searchName" allow-clear class="search-input" placeholder="请输入流程名称检索">
            <template #prefix>
              <SearchOutlined />
            </template>
          </a-input>
        </template>

        <a-tabs
          v-if="availableCategories.length"
          v-model:active-key="activeCategory"
          :tab-position="compactViewport ? 'top' : 'left'"
        >
          <a-tab-pane v-for="category in availableCategories" :key="category.code" :tab="category.name">
            <a-row :gutter="[16, 16]">
              <a-col
                v-for="definition in processDefinitionGroup[category.code]"
                :key="definition.id"
                :lg="6"
                :md="8"
                :sm="12"
                :xl="6"
                :xs="24"
              >
                <a-card class="process-card" hoverable @click="handleSelect(definition)">
                  <div class="process-card-content">
                    <img v-if="definition.icon" :src="definition.icon" alt="流程图标" class="process-icon" />
                    <div v-else class="process-icon fallback-icon">
                      {{ definition.name?.slice(0, 2) }}
                    </div>
                    <div class="process-info">
                      <a-tooltip :title="definition.name">
                        <div class="process-name">{{ definition.name }}</div>
                      </a-tooltip>
                      <a-tooltip v-if="definition.description" :title="definition.description">
                        <div class="process-desc">{{ definition.description }}</div>
                      </a-tooltip>
                    </div>
                  </div>
                </a-card>
              </a-col>
            </a-row>
          </a-tab-pane>
        </a-tabs>

        <a-empty v-else class="empty-state" description="暂无可发起流程" />
      </a-card>
    </template>

    <ProcessDefinitionForm
      v-else
      ref="processDefinitionFormRef"
      :select-process-definition="selectedProcessDefinition"
      @cancel="handleBack"
    />
  </div>
</template>

<style scoped>
.bpm-process-create-page {
  height: 100%;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.search-input {
  width: 320px;
}

.process-card {
  cursor: pointer;
}

.process-card :deep(.ant-card-body) {
  padding: 16px;
}

.process-card-content {
  display: flex;
  align-items: center;
  min-width: 0;
}

.process-icon {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  border-radius: 6px;
  object-fit: contain;
}

.fallback-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  background: #1677ff;
}

.process-info {
  min-width: 0;
  margin-left: 12px;
}

.process-name {
  overflow: hidden;
  font-size: 15px;
  color: #1f2937;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.process-desc {
  margin-top: 4px;
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-state {
  padding: 96px 0;
}
@media (max-width: 767px) {
  .search-input {
    width: 100%;
  }
  .bpm-process-create-page :deep(.ant-card-head-wrapper) {
    flex-wrap: wrap;
    gap: 8px;
    padding-block: 12px;
  }
  .bpm-process-create-page :deep(.ant-card-extra) {
    width: 100%;
    margin-left: 0;
  }
  .bpm-process-create-page :deep(.ant-card-body) {
    padding: 12px;
  }
}
</style>
