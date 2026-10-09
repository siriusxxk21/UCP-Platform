<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onMounted, ref } from 'vue'
import { Button, Input, Table, Tag } from 'ant-design-vue'
import FcDesigner from '@form-create/antd-designer'
import type { Config } from '@form-create/antd-designer'
import type { Rule } from '@form-create/ant-design-vue'

const designer = ref<InstanceType<typeof FcDesigner>>()
const active = ref('尚未选择'),
  status = ref('未保存'),
  snapshot = ref(''),
  preview = ref(false)
const operationCount = ref(0),
  checks = ref<string[]>([]),
  dragChecks = ref(0)
const previewRules = ref<Rule[]>([])
const RuntimeForm = FcDesigner.formCreate
const options = { form: { layout: 'vertical' }, submitBtn: false, resetBtn: false }
const storageKey = 'richuang.page-container-probe.v1'
const menu = [
  {
    name: 'layout',
    title: '容器',
    list: [
      { name: 'aTabs', label: '页签容器', icon: 'icon-tab' },
      { name: 'fcRow', label: '栅格容器', icon: 'icon-row' },
      { name: 'aCard', label: '卡片容器', icon: 'icon-card' }
    ]
  },
  { name: 'probe', title: '业务内容', list: [] }
]
const config: Config = {
  showAi: false,
  showFormConfig: false,
  showBaseForm: false,
  showValidateForm: false,
  showEventForm: false,
  showControl: false,
  showCustomProps: false,
  showStyleForm: false,
  showComponentName: true,
  showSaveBtn: false,
  showPreviewBtn: false,
  showJsonPreview: false,
  showDevice: true,
  showLanguage: false,
  showInputData: false,
  fieldReadonly: true,
  nameReadonly: true,
  autoResetName: true,
  formOptions: options,
  componentPermission: [{ name: 'page_root', permission: { delete: false, copy: false, move: false } }],
  // 模拟页面约束：本次仅允许业务内容块放在卡片中。分别验证画布和结构树是否都经过这个接口。
  checkDrag: ({ menu: source, toMenu: target }) => {
    dragChecks.value++
    return source.name !== 'probeList' || target.name === 'aCard'
  },
  componentRule: {
    aCard: () => [
      { type: 'input', field: 'title', title: '容器标题' },
      { type: 'inputNumber', field: 'bodyStyle>padding', title: '内容内边距', props: { min: 0, max: 64 } }
    ],
    aTabPane: () => [
      { type: 'input', field: 'tab', title: '页签名称' },
      { type: 'input', field: 'key', title: '页签标识', props: { disabled: true } }
    ]
  }
}

const ListBlock = defineComponent({
  props: { title: String, source: String },
  setup: p => () =>
    h('section', { class: 'business-list', 'data-probe-list': p.source }, [
      h('div', { class: 'list-heading' }, [
        h('strong', p.title || '公司档案列表'),
        h(Tag, { color: 'purple' }, () => '模拟数据')
      ]),
      h('div', { class: 'list-tools' }, [
        h(Input, { placeholder: '搜索公司', style: { maxWidth: '180px' } }),
        h(Button, { type: 'primary', onClick: () => operationCount.value++ }, () => '新增公司')
      ]),
      h(Table, {
        size: 'small',
        pagination: false,
        rowKey: 'id',
        columns: [
          { title: '公司编码', dataIndex: 'code' },
          { title: '公司名称', dataIndex: 'name' },
          { title: '经营状态', dataIndex: 'state' }
        ],
        dataSource: [
          { id: '1', code: 'COM-001', name: '华东实业集团', state: p.source === 'archived' ? '停用' : '正常经营' },
          { id: '2', code: 'COM-002', name: '上海创新科技', state: p.source === 'archived' ? '停用' : '正常经营' }
        ]
      })
    ])
})
const FormBlock = defineComponent({
  setup: () => () =>
    h('section', { class: 'business-list' }, [
      h('strong', '公司信息表单（模拟）'),
      h(RuntimeForm, {
        rule: [
          { type: 'input', field: 'company', title: '公司名称', value: '表单引擎复用验证' },
          { type: 'switch', field: 'enabled', title: '是否启用', value: true }
        ],
        option: options
      }),
      h(Button, { onClick: () => operationCount.value++ }, () => '模拟保存')
    ])
})
FcDesigner.component('probeList', ListBlock, ListBlock)
FcDesigner.component('probeForm', FormBlock, FormBlock)

function list(name: string, source = 'all'): Rule {
  return { type: 'probeList', name, title: '', props: { title: source === 'all' ? '全部公司' : '停用公司', source } }
}
const initial: Rule[] = [
  {
    type: 'aCard',
    name: 'page_root',
    props: { title: '公司档案页面', bodyStyle: { padding: 20 } },
    children: [
      {
        type: 'aTabs',
        name: 'company_tabs',
        props: new URLSearchParams(location.search).has('fixedTab') ? { activeKey: 'all' } : {},
        children: [
          {
            type: 'aTabPane',
            name: 'tab_all',
            props: { tab: '全部公司', key: 'all', forceRender: true },
            children: [
              {
                type: 'fcRow',
                name: 'main_grid',
                props: { gutter: 16 },
                children: [
                  {
                    type: 'col',
                    name: 'col_list',
                    props: { span: 16 },
                    children: [
                      {
                        type: 'aCard',
                        name: 'list_card',
                        props: { title: '公司列表容器', bodyStyle: { padding: 12 } },
                        children: [list('list_all')]
                      }
                    ]
                  },
                  {
                    type: 'col',
                    name: 'col_summary',
                    props: { span: 8 },
                    children: [
                      {
                        type: 'aCard',
                        name: 'summary_card',
                        props: { title: '摘要容器' },
                        children: [{ type: 'probeForm', name: 'company_form', props: {} }]
                      }
                    ]
                  }
                ]
              }
            ]
          },
          {
            type: 'aTabPane',
            name: 'tab_archived',
            props: { tab: '停用公司', key: 'archived', forceRender: true },
            children: [
              {
                type: 'aCard',
                name: 'archive_card',
                props: { title: '停用列表容器' },
                children: [list('list_archived', 'archived')]
              }
            ]
          }
        ]
      }
    ]
  }
]
function walk(rules: Rule[]): Rule[] {
  return rules.flatMap(r => [r, ...walk((r.children || []).filter(x => typeof x === 'object') as Rule[])])
}
// 只比较稳定的业务配置，排除引擎注入的绘图标记；不作为正式平台存储协议。
function normalized(rules: Rule[]): unknown[] {
  return rules.map(r => ({
    type: r.type === 'row' ? 'fcRow' : r.type,
    name: r.name,
    props: r.props || {},
    children: normalized((r.children || []).filter(x => typeof x === 'object') as Rule[])
  }))
}
function inspect() {
  const rules = designer.value!.getRule(),
    nodes = walk(rules)
  checks.value = [
    `节点 ${nodes.length} 个，稳定标识 ${new Set(nodes.map(n => n.name)).size} 个`,
    `页签 ${nodes
      .filter(n => n.type === 'aTabPane')
      .map(n => n.props?.key + ':' + n.props?.tab)
      .join(' / ')}`,
    `列表 ${nodes
      .filter(n => n.type === 'probeList')
      .map(n => n.name + ':' + n.props?.source)
      .join(' / ')}`,
    `跨页签节点保留：${nodes.some(n => n.name === 'list_archived')}`
  ]
  snapshot.value = JSON.stringify(rules, null, 2)
}
function save() {
  inspect()
  localStorage.setItem(storageKey, snapshot.value)
  status.value = '验证配置已保存到本地'
}
async function restore() {
  const text = localStorage.getItem(storageKey)
  if (!text) {
    status.value = '没有验证快照'
    return
  }
  const saved = JSON.parse(text)
  designer.value!.setRule(saved)
  await nextTick()
  status.value =
    JSON.stringify(normalized(saved)) === JSON.stringify(normalized(designer.value!.getRule()))
      ? '保存恢复：稳定结构一致'
      : '保存恢复：存在差异，请检查'
  inspect()
}
function run() {
  previewRules.value = designer.value!.getRule()
  preview.value = true
}
function selected(rule: Rule) {
  active.value = String(rule.name || rule.type)
}
const activeText = computed(
  () => `当前选择：${active.value}；业务按钮执行：${operationCount.value}；拖入校验：${dragChecks.value}`
)
onMounted(async () => {
  for (const [name, label] of [
    ['probeList', '公司数据列表'],
    ['probeForm', '公司信息表单']
  ])
    designer.value!.addComponent({
      name,
      label,
      menu: 'probe',
      icon: 'icon-table',
      languageKey: [],
      mask: true,
      rule: () => ({ type: name, name: crypto.randomUUID(), props: { title: label, source: 'all' } }),
      props: () => [
        { type: 'input', field: 'title', title: '内容标题' },
        {
          type: 'select',
          field: 'source',
          title: '数据来源',
          options: [
            { label: '全部公司', value: 'all' },
            { label: '停用公司', value: 'archived' }
          ]
        }
      ]
    })
  designer.value!.setRule(initial)
  await nextTick()
  inspect()
})
</script>

<template>
  <a-config-provider :theme="{ token: { colorPrimary: '#4f46e5', borderRadius: 6 } }">
    <header class="toolbar">
      <strong>页面容器技术验证</strong>
      <span>form-create 3.5.0 · 模拟数据 · 不写业务库</span>
      <a-button @click="save">保存验证配置</a-button>
      <a-button @click="restore">重载验证配置</a-button>
      <a-button @click="inspect">检查结构</a-button>
      <a-button type="primary" @click="run">运行预览</a-button>
    </header>
    <div class="status">
      <span>{{ activeText }}</span>
      <b>{{ status }}</b>
    </div>
    <FcDesigner ref="designer" :menu="menu" :config="config" height="calc(100vh - 170px)" @active="selected" />
    <footer>
      <span v-for="line in checks" :key="line">{{ line }}</span>
    </footer>
    <details>
      <summary>当前验证配置 JSON</summary>
      <pre>{{ snapshot }}</pre>
    </details>
    <a-modal v-model:open="preview" title="页面运行预览（模拟数据）" :width="1400" :footer="null" destroy-on-close>
      <RuntimeForm :rule="previewRules" :option="options" />
      <p>业务按钮执行：{{ operationCount }}</p>
    </a-modal>
  </a-config-provider>
</template>
<style>
body {
  margin: 0;
  color: #17223b;
  background: #f3f5f9;
  font-family: 'Microsoft YaHei', sans-serif;
}
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px 18px;
  background: white;
  flex-wrap: wrap;
}
.toolbar > span {
  margin-right: auto;
  color: #64748b;
  font-size: 12px;
}
.status {
  display: flex;
  justify-content: space-between;
  padding: 8px 18px;
  font-size: 12px;
}
.status b {
  color: #15803d;
}
.business-list {
  min-width: 0;
  background: white;
  padding: 8px;
}
.list-heading,
.list-tools {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  margin-bottom: 14px;
}
footer {
  padding: 8px 18px;
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  font-size: 12px;
}
details {
  margin: 0 18px;
}
pre {
  max-height: 260px;
  overflow: auto;
}
</style>
