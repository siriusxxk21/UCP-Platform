import { defineComponent, h } from 'vue'
import type { Rule } from '@form-create/ant-design-vue'

/** 开了「来源变化时自动更新」的字段在表单标签旁、列表列头上的标识与提示文字（第一期契约 9.4）。 */
export const AUTO_UPDATE_TEXT = '系统自动更新'
/** 在表单引擎里注册的组件名。 */
export const AUTO_UPDATE_MARK = 'nocodeAutoUpdateMark'
/** 小标识：样式与控件右上角的「联动 / 公式」标记一致；提示文字由外层的悬浮提示给出。 */
export const AutoUpdateMark = defineComponent({
  name: 'NocodeAutoUpdateMark',
  setup: () => () =>
    h(
      'span',
      {
        class: 'nocode-auto-update-mark',
        'aria-label': AUTO_UPDATE_TEXT,
        style: {
          display: 'inline-block',
          marginLeft: '6px',
          padding: '0 4px',
          borderRadius: '2px',
          background: 'var(--os-color-primary-bg, #e6f4ff)',
          color: 'var(--os-color-primary, #1677ff)',
          fontSize: '11px',
          fontWeight: 'normal',
          lineHeight: '16px',
          whiteSpace: 'nowrap',
          cursor: 'default'
        }
      },
      '自动更新'
    )
})
/** 表单引擎的标签提示配置：标签文字后面跟一个标识，悬浮显示「系统自动更新」。 */
export const autoUpdateInfo = (): Rule['info'] => ({
  info: AUTO_UPDATE_TEXT,
  type: 'tooltip',
  icon: AUTO_UPDATE_MARK,
  align: 'left'
})
