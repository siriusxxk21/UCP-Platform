import { defineComponent, h } from 'vue'
import { Row, Col, Alert, Button } from 'ant-design-vue'
import {
  pageNodeStyle,
  appearanceFromProps,
  styleFields,
  displayFields,
  actionFields
} from '../src/nocode/page-appearance'
import { NodeKind, PageButtonType, PageImageFit } from '../src/types/nocode/application-ui'
import './designer.css'

const props = {
  text: String,
  resourceId: String,
  taskViewJson: String,
  engineJson: String,
  relationId: String,
  direction: String,
  span: Number,
  imageUrl: String,
  ...Object.fromEntries(
    [
      ...styleFields.map(k => `style_${k}`),
      ...displayFields.map(k => `display_${k}`),
      ...actionFields.map(k => `action_${k}`)
    ].map(k => [k, { type: [String, Number, Boolean], default: undefined }])
  )
}
const style = (p, type) => pageNodeStyle({ type, ...appearanceFromProps(p) })
const container = (name, className, heading, type = NodeKind.CARD) =>
  defineComponent({
    name,
    props,
    setup:
      (p, { slots }) =>
      () =>
        h('section', { class: className, style: style(p, type) }, [
          heading ? h('header', p.text || heading) : null,
          ...(slots.default?.() || [])
        ])
  })
// 设计态展开所有页签，结构树选择始终对应可见 DOM；运行态按选中页签展示。
const block = (name, label, list = false) =>
  defineComponent({
    name,
    props,
    setup: p => () =>
      h('section', { class: 'os-block', style: style(p, NodeKind.VIEW) }, [
        h('header', p.text || label),
        h(
          'small',
          name === 'OsTasks'
            ? '运行时展示当前范围内有权查看的任务'
            : p.resourceId
              ? '已选择业务资源 · 运行时按应用权限加载'
              : '在右侧选择业务资源'
        ),
        list
          ? h('div', { class: 'os-table' }, [
              h('div', name === 'OsTasks' ? '任务名称　　执行人　　执行状态' : '业务字段　　业务字段　　业务字段'),
              h('p', name === 'OsTasks' ? '运行时显示真实任务数据' : '运行时显示真实业务数据'),
              h('p', name === 'OsTasks' ? '查询、分页与操作随任务配置' : '查询、分页与操作随资源配置')
            ])
          : h('div', { class: 'os-placeholder' }, '运行预览中查看内容与录入交互')
      ])
  })
export const components = {
  OsRow: defineComponent({
    props,
    setup:
      (p, { slots }) =>
      () =>
        h(Row, { gutter: p.style_gap ?? 16, style: style(p, NodeKind.ROW) }, slots)
  }),
  OsCol: defineComponent({
    props,
    setup:
      (p, { slots }) =>
      () =>
        h(Col, { xs: 24, md: p.span || 12 }, () => h('div', { style: style(p, NodeKind.COLUMN) }, slots.default?.()))
  }),
  OsCard: container('OsCard', 'os-card', '卡片'),
  OsTabs: container('OsTabs', 'os-tabs', '页签容器 · 设计时展开全部内容'),
  OsTab: container('OsTab', 'os-tab', '页签'),
  OsFlex: container('OsFlex', 'os-flex', null, NodeKind.FLEX),
  OsSpacer: defineComponent({
    props,
    setup: p => () =>
      h(
        'div',
        { class: 'os-spacer', style: { ...style(p, NodeKind.SPACER), height: `${p.style_minHeight ?? 24}px` } },
        '留白'
      )
  }),
  OsHeading: defineComponent({
    props,
    setup: p => () =>
      h(
        `h${p.display_headingLevel || 2}`,
        { class: 'os-heading', style: style(p, NodeKind.HEADING) },
        p.text || '页面标题'
      )
  }),
  OsImage: defineComponent({
    props,
    setup: p => () =>
      h(
        'div',
        { style: style(p, NodeKind.IMAGE) },
        p.imageUrl
          ? h('img', {
              src: p.imageUrl,
              alt: p.display_imageAlt || '页面图片',
              style: {
                width: '100%',
                height: `${p.display_imageHeight || 160}px`,
                objectFit: p.display_imageFit === PageImageFit.COVER ? 'cover' : 'contain'
              }
            })
          : h('div', { class: 'os-image-placeholder' }, '在右侧上传展示图片')
      )
  }),
  OsAlert: defineComponent({
    props,
    setup: p => () =>
      h(
        'div',
        { style: style(p, NodeKind.ALERT) },
        h(Alert, {
          showIcon: true,
          type: String(p.display_alertType || 'INFO').toLowerCase(),
          message: p.text || '提示信息'
        })
      )
  }),
  OsButton: defineComponent({
    props,
    setup: p => () =>
      h(
        'span',
        { style: style(p, NodeKind.BUTTON) },
        h(
          Button,
          {
            type:
              p.display_buttonType === PageButtonType.PRIMARY
                ? 'primary'
                : p.display_buttonType === PageButtonType.TEXT
                  ? 'text'
                  : 'default'
          },
          () => p.text || '操作按钮'
        )
      )
  }),
  OsText: defineComponent({
    props,
    setup: p => () => h('p', { class: 'os-text', style: style(p, NodeKind.TEXT) }, p.text || '说明文字')
  }),
  OsDivider: defineComponent({
    props,
    setup: p => () => h('hr', { class: 'os-divider', style: style(p, NodeKind.DIVIDER) })
  }),
  OsView: block('OsView', '数据列表', true),
  OsReport: block('OsReport', '统计报表'),
  OsReportDashboard: block('OsReportDashboard', '报表看板'),
  OsRelated: block('OsRelated', '相关列表', true),
  OsForm: block('OsForm', '业务表单'),
  OsDetail: block('OsDetail', '记录详情'),
  OsAttachments: block('OsAttachments', '记录附件'),
  OsProcesses: block('OsProcesses', '审批记录'),
  OsTasks: block('OsTasks', '任务列表', true),
  OsMetric: block('OsMetric', '记录统计'),
  // 设计态只画占位：运行时才按当前记录向系统申请令牌并以 iframe 加载引擎。
  OsEngine: defineComponent({
    name: 'OsEngine',
    props,
    setup: p => () =>
      h('section', { class: 'os-block', style: style(p, NodeKind.VIEW) }, [
        h('header', p.text || '设计引擎'),
        h('small', '运行时按当前记录打开设计引擎（每条记录一份设计）'),
        h('div', { class: 'os-placeholder', style: { minHeight: '160px' } }, '设计引擎画布 · 运行预览中加载')
      ])
  })
}
