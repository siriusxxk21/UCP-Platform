import request from '@/utils/request'
import type { PageResult } from '@/types/common'

const BASE = '/msg/template'

export function pageSysMsgTemplate(params: { current: number; size: number; keyword?: string }): Promise<any> {
  return request.get(`${BASE}/page`, { params })
}

export function listAllSysMsgTemplate(): Promise<any[]> {
  return request.get(`${BASE}/list`)
}

export function addSysMsgTemplate(data: any) {
  return request.post(`${BASE}/add`, data)
}

export function editSysMsgTemplate(data: any) {
  return request.post(`${BASE}/edit`, data)
}

export function getSupportChannels(): Promise<{ code: string; name: string; description: string; metadataList: { code: string; name: string; description: string }[] }[]> {
  return request.get(`${BASE}/supportChannels`)
}

export function deleteSysMsgTemplate(id: number) {
  return request.delete(`${BASE}/delete`, { params: { id } })
}

export interface MsgTemplateTarget {
  id?: string | number
  targetType: 'USER' | 'ROLE'
  targetId: string
  targetName: string
}

/** 查询当前租户的模板默认接收对象。 */
export function getTemplateTargets(templateId: string | number): Promise<MsgTemplateTarget[]> {
  return request.get(`${BASE}/${templateId}/targets`)
}

/** 覆盖保存当前租户的模板默认接收对象。 */
export function saveTemplateTargets(templateId: string | number, targets: MsgTemplateTarget[]): Promise<boolean> {
  return request.put(`${BASE}/${templateId}/targets`, targets)
}

/** 发送消息 */
const MSG_BASE = '/msg'

/** 获取支持的目标类型列表 */
export function supportTargetTypies(): Promise<{ code: string; name: string }[]> {
  return request.get(`${MSG_BASE}/supportTargetTypies`)
}

/** 发送消息（适配 MsgSendParam） */
export function sendMessage(data: {
  msgCode?: string
  title: string
  content?: string
  msgData?: Record<string, any>
  sourceType?: string
  sourceId?: string
  targets: { targetType: { code: string; name: string }; targetId: string; targetName: string }[]
  properties?: Record<string, any>
}): Promise<string> {
  return request.post(`${MSG_BASE}/send`, data)
}

/** 我的消息 - 通知相关 API */
const NOTICE_BASE = '/msg/notice'

/** 我的消息通知 VO */
export interface SysMsgNoticeVO {
  id: number
  msgId: number
  msgTemplateId: number
  title: string
  content: string
  url: string,
  sourceType: string,
  sourceId: string,
  priority: number
  ownerName: string
  hasRead: number
  noticeTime: string
  readTime: string
  sendTime: string
}

/** 分页查询我的消息 */
export function pageMyNotice(params: { pageNo: number; pageSize: number; msgTemplateId?: number; hasRead?: number; keyword?: string }): Promise<PageResult<SysMsgNoticeVO>> {
  return request.get(`${NOTICE_BASE}/page`, { params })
}

/** 标记消息为已读 */
export function markNoticeRead(ids: number[]): Promise<boolean> {
  return request.put(`${NOTICE_BASE}/read`, ids)
}

/** 获取未读消息数量 */
export function getUnreadCount(): Promise<number> {
  return request.get(`${NOTICE_BASE}/unread-count`)
}

/** 获取消息详情 */
export function getNoticeDetail(id: string): Promise<SysMsgNoticeVO> {
  return request.get(`${NOTICE_BASE}/${id}`)
}
