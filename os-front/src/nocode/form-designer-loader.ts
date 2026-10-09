/** 表单设计器挂载时才请求引擎；模块失败沿用底座刷新恢复。 */
export async function loadFormDesigner() {
  return (await import('@form-create/antd-designer')).default
}
