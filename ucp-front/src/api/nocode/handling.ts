import type { NocodeHttpClient } from './object'
import type { HandlingRequest, HandlingDetail, HandlingState } from '@/types/nocode/handling'
import type { Page } from '@/types/nocode/data-center'

export function createHandlingApi(client: NocodeHttpClient) {
  const root = '/nocode/handling'
  return {
    mine: (query: { pageNo: number; pageSize: number; status?: HandlingState; applicationId?: string }) =>
      client.post<Page<HandlingRequest>>(`${root}/mine`, query),
    detail: (id: string, taskId?: string) => client.get<HandlingDetail>(`${root}/detail`, { params: { id, taskId } }),
    reopen: (id: string) => client.post<import('@/types/nocode/handling').HandlingReopen>(`${root}/reopen`, { id }),
    withdraw: (id: string, expectedRevision: number, reason: string) =>
      client.post<HandlingRequest>(`${root}/withdraw`, { id, expectedRevision, reason }),
    retry: (id: string, expectedRevision: number) =>
      client.post<HandlingRequest>(`${root}/retry`, { id, expectedRevision })
  }
}
