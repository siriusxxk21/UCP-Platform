<script setup lang="ts">
import { computed, provide, reactive, ref, shallowReactive } from 'vue'
import { reportDashboardKey } from '@/nocode/report-context'
import type { ReportFilter } from '@/types/nocode/report'
import ReportBlock from './ReportBlock.vue'
import ApplicationDashboardBlock from './ApplicationDashboardBlock.vue'
import ReportDashboardFilters from './ReportDashboardFilters.vue'
import { NodeKind, PageAlertType, type UiNode, type FormConfig } from '@/types/nocode/application-ui'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import BusinessBlock from './BusinessBlock.vue'
import RecordExtras from './RecordExtras.vue'
import { pageNodeStyle } from '@/nocode/page-appearance'
import { blockRefreshId, usePageRefresh } from '@/nocode/application-context'
import { provideReadOnly } from '@/nocode/record-read-only'
import PageImage from './PageImage.vue'
import PageActionButton from './PageActionButton.vue'
import PageTasks from './PageTasks.vue'
import PageEngine from './PageEngine.vue'
import { tabSearchOwner } from '@/nocode/runtime-search-placement'
/** 页签行右侧给页签里唯一那个列表的查询栏留的挂载点：nodeId = 那个列表节点。 */
interface SearchSlot {
  nodeId: string
  target: HTMLElement | null
}
const props = defineProps<{
  nodes: UiNode[]
  applicationId: string
  resources: ApplicationResource[]
  recordId?: string
  pageId?: string
  nested?: boolean
  standalone?: boolean
  /** 只读约束（如正看着的记录已被别人删除）：页面里的编辑、动作按钮随之隐藏；只能收不能放。 */
  readOnly?: boolean
  /** 上一层页签交下来的查询栏挂载点：只交给 id 相符的那个列表节点。 */
  searchSlot?: SearchSlot
}>()
provideReadOnly(() => props.readOnly)
if (!props.nested)
  provide(reportDashboardKey, {
    definitions: computed(
      () => (props.resources.find(r => r.id === props.pageId)?.config.filters || []) as ReportFilter[]
    ),
    values: ref({})
  })
const refresh = usePageRefresh()
const refreshKey = (node: UiNode) => refresh.value[blockRefreshId(props.pageId, props.recordId, node.id)] || 0
// 页签容器：当前选中的页签（未切换过 = 第一个），以及每个页签在标签栏右侧的查询栏挂载点。
const activeTabs = reactive<Record<string, string>>({})
const searchTargets = shallowReactive<Record<string, HTMLElement | null>>({})
const activeTab = (tabs: UiNode) => activeTabs[tabs.id] ?? tabs.children[0]?.id
const searchTabs = (tabs: UiNode) => tabs.children.filter(tab => tabSearchOwner(tab))
function setSearchTarget(tabId: string, el: unknown) {
  const next = el instanceof HTMLElement ? el : null
  if (searchTargets[tabId] !== next) searchTargets[tabId] = next
}
function tabSearchSlot(tab: UiNode): SearchSlot | undefined {
  const nodeId = tabSearchOwner(tab)
  return nodeId ? { nodeId, target: searchTargets[tab.id] ?? null } : undefined
}
const alerts = {
  [PageAlertType.INFO]: 'info',
  [PageAlertType.SUCCESS]: 'success',
  [PageAlertType.WARNING]: 'warning',
  [PageAlertType.ERROR]: 'error'
} as const
</script>
<template>
  <ReportDashboardFilters v-if="!nested" :application-id="applicationId" />
  <div
    v-for="node in nodes"
    :key="node.id"
    class="page-node"
    :class="{ 'page-flex': node.type === NodeKind.FLEX, 'page-node--standalone': standalone && !nested }"
    :style="node.type === NodeKind.CARD ? undefined : pageNodeStyle(node)"
  >
    <a-row v-if="node.type === NodeKind.ROW" :gutter="node.style?.gap ?? 16">
      <a-col v-for="column in node.children" :key="column.id" :xs="24" :md="column.span || 12">
        <div :style="pageNodeStyle(column)">
          <PageRenderer
            nested
            :nodes="column.children"
            :application-id="applicationId"
            :resources="resources"
            :record-id="recordId"
            :page-id="pageId"
          />
        </div>
      </a-col>
    </a-row>
    <a-card
      v-else-if="node.type === NodeKind.CARD"
      :title="node.text"
      class="layout-card"
      :style="pageNodeStyle(node)"
      :body-style="node.style?.padding != null ? { padding: 0 } : undefined"
    >
      <PageRenderer
        nested
        :nodes="node.children"
        :application-id="applicationId"
        :resources="resources"
        :record-id="recordId"
        :page-id="pageId"
      />
    </a-card>
    <a-tabs
      v-else-if="node.type === NodeKind.TABS"
      class="page-tabs"
      :class="{ 'page-tabs--search': searchTabs(node).length > 0 }"
      :default-active-key="node.children[0]?.id"
      @change="(key: string | number) => (activeTabs[node.id] = String(key))"
    >
      <!-- 页签里直接只放了一个列表：它的查询栏挂到标签栏右侧，与页签同一行；只显示当前页签的。 -->
      <template v-if="searchTabs(node).length" #rightExtra>
        <div
          v-for="tab in searchTabs(node)"
          v-show="activeTab(node) === tab.id"
          :key="tab.id"
          :ref="el => setSearchTarget(tab.id, el)"
          class="page-tabs__search"
          :data-tab-search="tab.id"
        />
      </template>
      <a-tab-pane v-for="tab in node.children" :key="tab.id" :tab="tab.text || '未命名页签'">
        <div :style="pageNodeStyle(tab)">
          <PageRenderer
            nested
            :nodes="tab.children"
            :application-id="applicationId"
            :resources="resources"
            :record-id="recordId"
            :page-id="pageId"
            :search-slot="tabSearchSlot(tab)"
          />
        </div>
      </a-tab-pane>
    </a-tabs>
    <template v-else-if="[NodeKind.COLUMN, NodeKind.TAB, NodeKind.FLEX].some(type => type === node.type)">
      <PageRenderer
        nested
        :nodes="node.children"
        :application-id="applicationId"
        :resources="resources"
        :record-id="recordId"
        :page-id="pageId"
      />
    </template>
    <a-divider v-else-if="node.type === NodeKind.DIVIDER" />
    <p v-else-if="node.type === NodeKind.TEXT" class="page-text">{{ node.text }}</p>
    <component
      :is="`h${node.display?.headingLevel || 2}`"
      v-else-if="node.type === NodeKind.HEADING"
      class="page-heading"
    >
      {{ node.text }}
    </component>
    <PageImage v-else-if="node.type === NodeKind.IMAGE" :display="node.display" />
    <a-alert
      v-else-if="node.type === NodeKind.ALERT"
      :type="alerts[node.display?.alertType || PageAlertType.INFO]"
      :message="node.text || '提示信息'"
      show-icon
    />
    <PageActionButton
      v-else-if="node.type === NodeKind.BUTTON"
      :node="node"
      :application-id="applicationId"
      :resources="resources"
      :page-id="pageId"
      :record-id="recordId"
    />
    <div
      v-else-if="node.type === NodeKind.SPACER"
      :style="{ height: `${node.style?.minHeight ?? 24}px` }"
      aria-hidden="true"
    />
    <a-empty
      v-else-if="
        ([NodeKind.RELATED, NodeKind.DETAIL, NodeKind.ATTACHMENTS, NodeKind.PROCESSES].some(
          type => type === node.type
        ) &&
          !recordId) ||
        (node.type === NodeKind.REPORT && !!node.binding && !recordId)
      "
      description="请先从列表选择一条记录"
    />
    <RecordExtras
      v-else-if="
        [NodeKind.ATTACHMENTS, NodeKind.PROCESSES].some(type => type === node.type) &&
        recordId &&
        resources.some(r => r.id === node.resourceId)
      "
      :application-id="applicationId"
      :record-id="recordId"
      :kind="node.type"
      :title="node.text || undefined"
      :refresh-key="refreshKey(node)"
      :form="resources.find(r => r.id === node.resourceId)!.config as unknown as FormConfig"
    />
    <ReportBlock
      v-else-if="node.type === NodeKind.REPORT && resources.some(r => r.id === node.resourceId)"
      :application-id="applicationId"
      :resource="resources.find(r => r.id === node.resourceId)!"
      :resources="resources"
      :title="node.text || undefined"
      :refresh-key="refreshKey(node)"
      :context="node.binding && pageId && recordId ? { pageId, nodeId: node.id, recordId } : undefined"
    />
    <PageTasks
      v-else-if="node.type === NodeKind.TASKS"
      :application-id="applicationId"
      :resources="resources"
      :page-id="pageId"
      :node-id="node.id"
      :resource-id="node.resourceId"
      :task-view="node.taskView"
      :record-id="recordId"
      :title="node.text || undefined"
      :refresh-key="refreshKey(node)"
    />
    <PageEngine
      v-else-if="node.type === NodeKind.ENGINE"
      :application-id="applicationId"
      :page-id="pageId"
      :node-id="node.id"
      :record-id="recordId"
      :title="node.text || undefined"
    />
    <ApplicationDashboardBlock
      v-else-if="
        node.type === NodeKind.REPORT_DASHBOARD &&
        resources.some(r => r.id === node.resourceId && r.kind === ResourceKind.REPORT_DASHBOARD)
      "
      :application-id="applicationId"
      :resource="resources.find(r => r.id === node.resourceId)!"
      :resources="resources"
      :record-id="recordId"
      :title="node.text || undefined"
      :refresh-key="refreshKey(node)"
    />
    <a-empty v-else-if="node.type === NodeKind.REPORT_DASHBOARD" description="当前看板资源不可用" />
    <BusinessBlock
      v-else-if="node.resourceId"
      :standalone="standalone && !nested && node.type === NodeKind.VIEW"
      :application-id="applicationId"
      :resources="resources"
      :resource-id="node.resourceId"
      :metric="node.type === NodeKind.METRIC"
      :detail="node.type === NodeKind.DETAIL"
      :title="node.text || undefined"
      :refresh-key="refreshKey(node)"
      :search-target="searchSlot?.nodeId === node.id ? searchSlot.target : undefined"
      :record-id="[NodeKind.FORM, NodeKind.DETAIL].some(type => type === node.type) ? recordId : undefined"
      :context="
        node.type === NodeKind.RELATED && pageId && recordId ? { pageId, nodeId: node.id, recordId } : undefined
      "
    />
  </div>
</template>
<style scoped>
.layout-card,
.page-tabs {
  margin-bottom: 16px;
  border-radius: 8px;
}
.page-tabs {
  background: white;
  padding: 0 16px;
}
/* 标签栏右侧挂了查询栏：同一行放不下时查询栏整组换到下一行，不压缩页签；窄屏固定独占下一行。只管本层，不影响嵌套的页签。 */
.page-tabs--search > :deep(.ant-tabs-nav) {
  flex-wrap: wrap;
  column-gap: 16px;
}
.page-tabs--search > :deep(.ant-tabs-nav > .ant-tabs-nav-wrap) {
  flex: 1 0 auto;
  max-width: 100%;
}
.page-tabs--search > :deep(.ant-tabs-nav > .ant-tabs-extra-content) {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  margin-left: auto;
  padding: 6px 0;
}
.page-tabs__search {
  display: flex;
  justify-content: flex-end;
  min-width: 0;
}
@media (max-width: 768px) {
  .page-tabs--search > :deep(.ant-tabs-nav > .ant-tabs-extra-content) {
    flex: 1 1 100%;
    margin-left: 0;
  }
  .page-tabs__search {
    justify-content: flex-start;
  }
}
.page-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: inherit;
  line-height: 1.8;
  margin-bottom: 16px;
}
.page-node {
  min-width: 0;
}
.page-node--standalone {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.page-flex > .page-node {
  max-width: 100%;
}
.page-heading {
  color: inherit;
  margin: 0;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
</style>
