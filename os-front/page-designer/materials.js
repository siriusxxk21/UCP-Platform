import { CHILD_ONLY, PARENT_ONLY } from '../src/nocode/page-nesting'

const definitions = [
  // 图标复用已注册的 TinyEngine SVG；组件树与左侧物料卡片使用同一份语义映射。
  ['OsCard', '卡片', 'card', true],
  ['OsRow', '分栏容器', 'row', true],
  ['OsCol', '分栏', 'col', true],
  ['OsTabs', '页签容器', 'tabs', true],
  ['OsTab', '页签', 'tabitem', true],
  ['OsFlex', '弹性布局', 'flexbox', true],
  ['OsSpacer', '留白', 'padding'],
  ['OsHeading', '标题', 'h1'],
  ['OsImage', '图片', 'image'],
  ['OsAlert', '提示信息', 'alert'],
  ['OsButton', '操作按钮', 'button'],
  ['OsText', '说明文字', 'text'],
  ['OsDivider', '分隔线', 'hr'],
  ['OsView', '数据列表', 'table'],
  ['OsReport', '统计报表', 'achart'],
  ['OsReportDashboard', '报表看板', 'achart'],
  ['OsForm', '业务表单', 'form'],
  ['OsDetail', '记录详情', 'page'],
  ['OsRelated', '相关列表', 'link'],
  ['OsAttachments', '记录附件', 'file-upload'],
  ['OsProcesses', '审批记录', 'process'],
  ['OsTasks', '任务列表', 'list'],
  ['OsMetric', '记录统计', 'statistic'],
  ['OsEngine', '设计引擎', 'page']
]
const node = (componentName, text, children = []) => ({
  componentName,
  props: {
    text,
    span: 12,
    ...(componentName === 'OsButton' ? { action_kind: 'REFRESH', display_buttonType: 'PRIMARY' } : {}),
    ...(componentName === 'OsFlex' ? { style_gap: 12, style_marginBottom: 16 } : {}),
    ...(componentName === 'OsHeading' ? { display_headingLevel: 2, style_marginBottom: 16 } : {}),
    ...(componentName === 'OsSpacer' ? { style_minHeight: 24 } : {})
  },
  children
})
const groups = [
  ['布局容器', ['OsCard', 'OsRow', 'OsCol', 'OsTabs', 'OsTab', 'OsFlex', 'OsSpacer']],
  ['基础内容', ['OsHeading', 'OsText', 'OsImage', 'OsAlert', 'OsButton', 'OsDivider']],
  [
    '业务区块',
    [
      'OsView',
      'OsReport',
      'OsReportDashboard',
      'OsForm',
      'OsDetail',
      'OsRelated',
      'OsAttachments',
      'OsProcesses',
      'OsTasks',
      'OsMetric',
      'OsEngine'
    ]
  ]
]
export const materials = {
  components: definitions.map(([component, title, icon, container]) => ({
    component,
    name: { zh_CN: title },
    icon,
    configure: {
      isContainer: !!container,
      styles: false,
      // 与宿主、后端同一张父子约束表（拖拽时引擎只部分遵守，其余由 main.js 撤回不合规的一步）。
      nestingRule: {
        ...(CHILD_ONLY[component] ? { childWhitelist: [CHILD_ONLY[component]] } : {}),
        ...(PARENT_ONLY[component] ? { parentWhitelist: [PARENT_ONLY[component]] } : {})
      }
    },
    schema: { properties: [], events: {}, slots: container ? { default: { label: { zh_CN: '内容' } } } : {} }
  })),
  snippets: groups.map(([label, items], index) => ({
    // TinyEngine 按 group 合并物料，相同 group 会导致三个分组被折叠成一个。
    group: `os-materials-${index}`,
    label: { zh_CN: label },
    children: definitions
      .filter(([name]) => items.includes(name))
      .map(([componentName, title, icon]) => ({
        snippetName: componentName,
        name: { zh_CN: title },
        icon,
        schema: node(
          componentName,
          title,
          componentName === 'OsTabs'
            ? [node('OsTab', '基本信息'), node('OsTab', '相关业务')]
            : componentName === 'OsRow'
              ? [node('OsCol', ''), node('OsCol', '')]
              : []
        )
      }))
  }))
}
