import request from '@/utils/request'

export type FeedbackType = 'BUG' | 'REQUIREMENT'
export type FeedbackStatus = 'PENDING' | 'CLOSED'
export interface Feedback {
  id: string
  feedbackNo: string
  feedbackType: FeedbackType
  title: string
  description: string
  status: FeedbackStatus
  submitterName: string
  pageTitle?: string
  pagePath?: string
  buildCommit?: string
  createTime: string
  version: number
}
export interface FeedbackDetail extends Feedback {
  imageUrls: string[]
  followUps: {
    id: string
    operatorName: string
    content?: string
    fromStatus?: FeedbackStatus
    toStatus?: FeedbackStatus
    toStatusLabel?: string
    createTime: string
  }[]
}
export interface FeedbackQuery {
  keyword?: string
  type?: FeedbackType
  status?: FeedbackStatus
}

export function submitFeedback(data: FormData) {
  return request.post<string>('/system/feedback', data, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  })
}
export function getFeedbackPage(params: FeedbackQuery & { pageNum: number; pageSize: number }) {
  const { pageNum, ...rest } = params
  return request.get<{ list: Feedback[]; total: number }>('/system/feedback/admin/page', {
    params: { ...rest, pageNo: pageNum }
  })
}
export function getFeedbackDetail(id: string) {
  return request.get<FeedbackDetail>(`/system/feedback/admin/${id}`)
}
export function saveFeedback(id: string, data: { status: FeedbackStatus; content?: string; version: number }) {
  return request.put<boolean>(`/system/feedback/admin/${id}/follow-up`, data)
}
