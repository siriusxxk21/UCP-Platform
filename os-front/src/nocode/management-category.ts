/** 系统筛选节点与同名自定义分类使用不同标签，保存和查询仍保留用户原名。 */
export function managementCategoryLabel(category?: string): string {
  if (!category) return '未分类'
  return ['未分类', '全部分类'].includes(category) ? `${category}（自定义）` : category
}
