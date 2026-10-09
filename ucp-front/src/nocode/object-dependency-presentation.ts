import type { Dependency } from '@/types/nocode/data-center'

/** APP 登记表达对象版本引用，不等于每个字段都被表单或规则实际使用。 */
export function objectDependencyPresentation(dependency: Dependency) {
  const application =
    dependency.sourceKind === 'APP' ? /^application:(\d+):(draft|published)$/.exec(dependency.sourceKey) : null
  if (application)
    return {
      label: application[2] === 'draft' ? '应用草稿中的对象引用' : '已发布应用中的对象引用',
      description:
        '应用固定引用对象版本。实际变更是否受阻，由字段或发布检查中的具体资源影响决定；调整资源、同步对象版本和发布仍在应用中心完成。',
      route: `/nocode-app/workspace?id=${application[1]}`
    }
  return {
    label: `引用来源：${dependency.sourceKind}`,
    description: `来源标识：${dependency.sourceKey}。请根据操作前检查中的具体位置处理引用。`,
    route: null
  }
}
