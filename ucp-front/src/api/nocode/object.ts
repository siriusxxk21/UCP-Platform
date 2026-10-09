import type { ObjectDraft, ObjectPage, ObjectQuery, SaveObjectDraft } from '@/types/nocode/object'

/** The existing authenticated client is injected by main.ts. */
export interface NocodeHttpClient {
  get: <T>(
    path: string,
    options?: { params?: object; responseType?: 'blob'; timeout?: number; quiet?: boolean }
  ) => Promise<T>
  post: <T>(
    path: string,
    body: unknown,
    options?: {
      headers?: Record<string, string>
      responseType?: 'blob'
      timeout?: number
      signal?: AbortSignal
      quiet?: boolean
      quietCodes?: number[]
      onUploadProgress?: (progress: { loaded: number; total?: number }) => void
    }
  ) => Promise<T>
  put: <T>(path: string, body: unknown) => Promise<T>
}
export function createObjectApi(client: NocodeHttpClient) {
  return {
    page: (params: ObjectQuery) => client.get<ObjectPage>('/nocode/object/page', { params }),
    get: (id: string) => client.get<ObjectDraft>('/nocode/object/get', { params: { id } }),
    create: (body: SaveObjectDraft) => client.post<ObjectDraft>('/nocode/object/create', body),
    save: (body: SaveObjectDraft) => client.put<ObjectDraft>('/nocode/object/save-draft', body)
  }
}
export type ObjectApi = ReturnType<typeof createObjectApi>
