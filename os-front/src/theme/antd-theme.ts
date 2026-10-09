import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

// 品牌色系统：统一为 choerodon-style 靛蓝紫调
export const antdTheme: ThemeConfig = {
  token: {
    // ===== 品牌主色调 =====
    colorPrimary: '#4338CA',
    colorPrimaryHover: '#5B51D8',
    colorPrimaryActive: '#4C42B8',
    colorSuccess: '#10B981',
    colorWarning: '#F59E0B',
    colorError: '#EF4444',
    colorInfo: '#4338CA',
    colorLink: '#1677ff',
    colorLinkHover: '#1677ff',
    colorLinkActive: '#1677ff',

    // ===== 文字色 =====
    colorText: '#1F2937',
    colorTextSecondary: '#6B7280',
    colorTextTertiary: '#9CA3AF',
    colorTextPlaceholder: '#9CA3AF',

    // ===== 背景 / 边框 =====
    colorBgLayout: '#F5F6FA',
    colorBgContainer: '#FFFFFF',
    colorBgElevated: '#FFFFFF',
    colorBorder: '#E5E7EB',
    colorBorderSecondary: '#E5E7EB',
    controlItemBgHover: '#F5F3FF',
    controlItemBgActive: '#EEF2FF',
    controlOutline: 'rgba(67, 56, 202, 0.12)',

    // ===== 圆角 =====
    borderRadius: 6,
    borderRadiusLG: 8,
    borderRadiusSM: 4,

    // ===== 阴影（极淡） =====
    boxShadow: '0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
    boxShadowSecondary: '0 4px 12px rgba(0,0,0,0.06)',

    // ===== 字体 =====
    fontSize: 14,
    fontSizeSM: 12,
    fontSizeLG: 16,
    lineHeight: 1.5,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "HarmonyOS Sans", "Noto Sans CJK SC", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',

    // ===== 表单控件高度 =====
    controlHeight: 32,
    controlHeightSM: 28,
    controlHeightLG: 40
  },
  components: {
    // 卡片组件
    Card: {
      borderRadiusLG: 8,
      paddingLG: 24,
      colorBorder: '#E5E7EB',
      colorBgContainer: '#fff'
    },
    // 按钮组件
    Button: {
      borderRadius: 6,
      controlHeight: 32,
      controlHeightLG: 40,
      controlHeightSM: 24,
      colorPrimaryHover: '#5B51D8',
      colorPrimaryActive: '#4C42B8',
      colorLinkHover: '#4338CA' // 保持 type="link" 悬浮字体颜色不变
    },
    // 输入框组件
    Input: {
      borderRadius: 6,
      controlHeight: 32,
      colorBorder: '#E5E7EB',
      colorPrimaryHover: '#5B51D8'
    },
    // 选择器组件
    Select: {
      borderRadius: 6,
      controlHeight: 32
    },
    // 表格组件（ComponentToken 为空，仅支持 AliasToken 全局属性）
    Table: {
      borderRadiusLG: 8
    },
    // 菜单组件
    Menu: {
      colorItemBg: 'transparent',
      colorItemBgHover: 'rgba(67, 56, 202, 0.06)',
      colorItemBgSelected: 'rgba(67, 56, 202, 0.1)',
      colorItemTextSelected: '#4338CA'
    },
    // 模态框组件
    Modal: {
      borderRadiusLG: 8
    },
    // 标签组件
    Tag: {
      borderRadiusSM: 4
    }
  }
}
