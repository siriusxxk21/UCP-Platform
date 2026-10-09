import request from '@/utils/request'
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
/** 页面展示素材复用底座文件；不支持外站地址、SVG/HTML 或业务附件的自动转用。 */
export async function pageImageUrl(id: string): Promise<string> {
  if (!/^[1-9]\d{0,18}$/.test(id)) throw new Error('请选择展示图片')
  const files = (await request.get('/infra/file/get-list', { params: { ids: id } })) as unknown as Array<{
    id: string
    type: string
    configId: string
    path: string
  }>
  const file = files.find(f => String(f.id) === id)
  if (!file || !['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type))
    throw new Error('图片不存在或不是支持的图片格式')
  if (!/^\d+$/.test(String(file.configId))) throw new Error('图片存储配置无效')
  return `${baseURL}/infra/file/${file.configId}/get/${file.path.split('/').map(encodeURIComponent).join('/')}`
}
