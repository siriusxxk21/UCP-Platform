<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { antdTheme } from '../../src/theme/antd-theme'
import { nocodePlatformKey, type NocodePlatform } from '../../src/nocode/platform'
import { FieldType } from '../../src/types/nocode/enums'
import { NodeKind, uiNode, type UiNode } from '../../src/types/nocode/application-ui'
import type { PublishedDefinition } from '../../src/types/nocode/application'
import { businessFieldRules } from '../../src/nocode/business-field-rules'
import { arrangeFormColumns, formDesignModel } from '../../src/nocode/form-design'
import { defaultFormNodes } from '../../src/nocode/form-presentation'
import BusinessDesigner from '../../src/views/nocode/application/components/BusinessDesigner.vue'
import RecordForm from '../../src/views/nocode/application/components/RecordForm.vue'
import RecordReadView from '../../src/views/nocode/application/components/RecordReadView.vue'
import RecordEditor from '../../src/views/nocode/application/components/RecordEditor.vue'
import type { RecordModel, Aggregate } from '../../src/types/nocode/runtime'
import type { SelectionQuery } from '../../src/types/nocode/selection'
import ReferenceExample from './ReferenceExample.vue'

// 实际生产组件 + 内存夹具，禁止此验证工具写入应用配置或业务记录。
const selectionOptions = Array.from({ length: 12 }, (_, i) => ({
  value: `tag-${i}`,
  code: `tag-${i}`,
  label: `经营分类长名称${i + 1}`,
  parentValue: null,
  path: null,
  disabled: false,
  unavailable: false
}))
async function selection(query: SelectionQuery) {
  return {
    options: selectionOptions.filter(o => !query.search || o.label.includes(query.search)),
    selected: selectionOptions.filter(o => query.selected?.includes(o.value)),
    total: 12,
    tree: false,
    defaultValue: null
  }
}
provide(nocodePlatformKey, {
  applications: { previewSelection: ({ query }: { query: SelectionQuery }) => selection(query) },
  runtime: { selection }
} as unknown as NocodePlatform)
const fields = [
  ['name', '正式名称', FieldType.TEXT],
  ['short', '简称', FieldType.TEXT],
  ['alias', '假名', FieldType.TEXT],
  ['english', '英文名', FieldType.TEXT],
  ['legal', '法人番号', FieldType.TEXT],
  ['owner', '法人代表', FieldType.TEXT],
  ['directors', '董事', FieldType.TEXTAREA],
  ['capital', '注册资本', FieldType.MONEY],
  ['date', '成立日期', FieldType.DATE],
  ['tags', '经营分类', FieldType.MULTI_SELECT],
  ['enabled', '正常经营', FieldType.BOOLEAN],
  ['notes', '备注', FieldType.TEXTAREA]
].map(([id, name, type]) => ({ id, code: id, name, type, length: 1000, required: id === 'name' }))
const definition = {
  objectId: 'company',
  objectName: '公司',
  fields,
  fieldOptions: {
    tags: { options: Array.from({ length: 12 }, (_, i) => ({ code: `tag-${i}`, label: `经营分类长名称${i + 1}` })) }
  },
  relations: [],
  details: [],
  settings: {}
} as unknown as PublishedDefinition
const base = definition.fields.map(f => uiNode(NodeKind.FIELD, { fieldId: f.id }))
base[0]!.presentation = { help: '填写与登记资料一致的名称。' }
const nodes = ref<UiNode[]>(arrangeFormColumns(base, 2))
const revision = ref(0)
const designer = ref<InstanceType<typeof BusinessDesigner>>()
const layout = ref<'vertical' | 'horizontal'>('vertical')
const mode = ref(new URLSearchParams(location.search).get('example') === 'reference' ? 'reference' : 'design')
const narrow = ref(false)
const values = ref<Record<string, unknown>>({
  name: '日创企业管理有限公司',
  short: '日创',
  alias: '',
  english: 'RICHUANG',
  legal: '0123456789012',
  owner: '张明',
  directors: '张明\n王清',
  capital: '0',
  date: '2026-09-08',
  tags: ['tag-0', 'tag-1', 'tag-2'],
  enabled: false,
  notes: '支持长文本换行，详情不出现可编辑输入框。'
})
const fieldRules = computed(() =>
  businessFieldRules(definition.fields, definition.fieldOptions, formDesignModel, true, {
    mode: 'design',
    applicationId: 'fixture',
    objectId: 'company'
  })
)
const detail = { id: 'branches', name: '分支机构', state: 'ACTIVE', fields: [definition.fields[0]!], fieldOptions: {} }
const aggregateModel = {
  ...formDesignModel,
  object: { ...definition, details: [detail] },
  details: { branches: formDesignModel },
  permissions: {
    actions: ['READ', 'CREATE', 'UPDATE'],
    readFields: definition.fields.map(f => f.id),
    writeFields: definition.fields.map(f => f.id),
    readDetails: ['branches'],
    writeDetails: ['branches'],
    readRelations: [],
    writeRelations: []
  }
} as unknown as RecordModel
const aggregate = computed(
  () =>
    ({
      record: { id: 'example', revision: '1', values: values.value, displayValues: { tags: '经营分类1、经营分类2' } },
      details: { branches: [{ id: 'branch1', revision: '1', values: { name: '上海分公司' } }] }
    }) as Aggregate
)
function sync() {
  if (designer.value?.isReady()) nodes.value = designer.value.getNodes()
}
function open(next: string) {
  sync()
  mode.value = next
}
function grouped() {
  nodes.value = [
    uiNode(NodeKind.CARD, { text: '基本信息', children: arrangeFormColumns(base.slice(0, 6), 2) }),
    uiNode(NodeKind.DIVIDER),
    uiNode(NodeKind.TABS, {
      children: [
        uiNode(NodeKind.TAB, { text: '经营资料', children: arrangeFormColumns(base.slice(6, 10), 2) }),
        uiNode(NodeKind.TAB, { text: '其他信息', children: base.slice(10) })
      ]
    })
  ]
  revision.value++
}
function defaultLayout() {
  nodes.value = defaultFormNodes(definition.fields)
  revision.value++
}
</script>
<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <main class="spec-fixture">
      <header>
        <h1>业务表单与详情 · 统一展示规范</h1>
        <a-space wrap>
          <a-button @click="open('design')">设计画布</a-button>
          <a-button @click="open('edit')">运行编辑</a-button>
          <a-button @click="open('read')">运行详情</a-button>
          <a-button @click="open('aggregate')">含明细详情</a-button>
          <a-button @click="open('reference')">资产类型下拉单选</a-button>
          <a-button @click="grouped">分组与页签示例</a-button>
          <a-button @click="defaultLayout">默认布局示例</a-button>
          <a-switch v-model:checked="narrow" checked-children="窄容器" un-checked-children="桌面" />
          <a-radio-group
            v-model:value="layout"
            :options="[
              { label: '顶部标签', value: 'vertical' },
              { label: '左侧标签', value: 'horizontal' }
            ]"
          />
        </a-space>
      </header>
      <div v-if="mode === 'design'" class="design-host">
        <BusinessDesigner
          :key="revision"
          ref="designer"
          :nodes="nodes"
          :fields="fieldRules"
          :definition="definition"
          :resources="[]"
          form
          :form-options="{ layout }"
          name="公司信息表单"
          application-id="fixture"
          :objects="{}"
        />
      </div>
      <ReferenceExample v-else-if="mode === 'reference'" />
      <article v-else class="spec-paper" :class="{ narrow }">
        <h2>公司信息</h2>
        <p class="spec-caption">
          {{ mode === 'read' ? '查看公司登记资料与经营信息' : '请填写公司登记资料与经营信息' }}
        </p>
        <RecordForm
          v-if="mode === 'edit'"
          v-model="values"
          :fields="definition.fields"
          :options="definition.fieldOptions"
          :model="formDesignModel"
          creating
          :nodes="nodes"
          :layout="layout"
        />
        <RecordEditor
          v-else-if="mode === 'aggregate'"
          application-id="fixture"
          :model="aggregateModel"
          :record="aggregate"
          read-only
        />
        <RecordReadView
          v-else
          :fields="definition.fields"
          :options="definition.fieldOptions"
          :values="values"
          application-id="fixture"
          :relations="[]"
          :nodes="nodes"
          :layout="layout"
        />
      </article>
    </main>
  </a-config-provider>
</template>
<style scoped>
.spec-fixture {
  padding: 24px;
  min-height: 100vh;
  background: #f5f6fa;
}
header {
  margin-bottom: 20px;
}
h1 {
  margin: 0 0 16px;
  font-size: 20px;
}
.design-host {
  height: calc(100vh - 144px);
  min-height: 500px;
}
.spec-paper {
  max-width: 1000px;
  margin: 0 auto;
  padding: 24px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.spec-paper.narrow {
  width: 390px;
  max-width: 100%;
  padding: 16px;
}
h2 {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
}
.spec-caption {
  margin-bottom: 24px;
  color: #6b7280;
}
</style>
