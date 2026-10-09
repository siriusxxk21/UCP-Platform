/**
 * 应用图标注册表。
 *
 * 统一管理 Ant Design Icons 和项目 Iconfont 元数据；菜单字段继续使用字符串，
 * 从而兼容历史 Ant Design 图标名称，并通过前缀区分 Iconfont 图标。
 */
import * as AntDesignIcons from '@ant-design/icons-vue'
import { createFromIconfontCN } from '@ant-design/icons-vue'
import type { Component } from 'vue'
import iconfontMetadata from '@/assets/iconfont/iconfont.json'

const ICONFONT_VALUE_PREFIX = 'iconfont:'
const ANTD_VALUE_PREFIX = 'antd:'
const ANT_ICON_NAME_PATTERN = /(Filled|Outlined|TwoTone)$/

interface IconfontGlyph {
  name: string
  font_class: string
}

interface IconfontMetadata {
  glyphs: IconfontGlyph[]
}

export interface IconOption {
  label: string
  name: string
  value: string
}

/**
 * 本地 Iconfont Symbol 组件。
 * 使用 Vite BASE_URL，兼容部署在非根路径下的场景。
 */
export const IconFont = createFromIconfontCN({
  scriptUrl: `${import.meta.env.BASE_URL}iconfont/iconfont.js`
})

/** 全量 Ant Design Vue 图标映射，供菜单选择器和运行时渲染使用。 */
export const iconMap = Object.fromEntries(
  Object.entries(AntDesignIcons).filter(([name]) => ANT_ICON_NAME_PATTERN.test(name))
) as Record<string, Component>

/** 排序后的 Ant Design 图标列表。 */
export const antDesignIconList: IconOption[] = Object.keys(iconMap)
  .sort((left, right) => left.localeCompare(right))
  .map(name => ({
    label: name,
    name,
    value: name
  }))

/** 下载包内的 Iconfont 图标列表。 */
export const iconfontIconList: IconOption[] = ((iconfontMetadata as IconfontMetadata).glyphs || []).map(glyph => {
  const name = `icon-${glyph.font_class}`
  return {
    label: glyph.name || glyph.font_class,
    name,
    value: `${ICONFONT_VALUE_PREFIX}${name}`
  }
})

const iconfontNameSet = new Set(iconfontIconList.map(item => item.name))

/** 判断菜单图标值是否为 Iconfont。 */
export function isIconfontIcon(iconName?: string): boolean {
  return !!iconName?.startsWith(ICONFONT_VALUE_PREFIX)
}

/** 从菜单存储值中取得 Iconfont Symbol ID。 */
export function getIconfontType(iconName?: string): string | undefined {
  if (!isIconfontIcon(iconName)) return undefined
  return iconName?.slice(ICONFONT_VALUE_PREFIX.length) || undefined
}

/**
 * 根据图标名称取得 Ant Design Vue 组件。
 * 同时兼容历史的 HomeOutlined 和显式的 antd:HomeOutlined 写法。
 */
export function getIcon(iconName?: string): Component | null {
  if (!iconName || isIconfontIcon(iconName)) return null
  const normalizedName = iconName.startsWith(ANTD_VALUE_PREFIX) ? iconName.slice(ANTD_VALUE_PREFIX.length) : iconName
  return iconMap[normalizedName] || null
}

/** 判断图标值能否由当前图标资源渲染。 */
export function isSupportedIcon(iconName?: string): boolean {
  const iconfontType = getIconfontType(iconName)
  if (iconfontType) return iconfontNameSet.has(iconfontType)
  return !!getIcon(iconName)
}
