const DEFAULT_WS_PATH = '/infra/ws'

function getBaseWsUrl(path = DEFAULT_WS_PATH) {
  const wsHost = import.meta.env.VITE_WS_HOST
  if (wsHost) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${wsHost}${path}`
  }

  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || ''
  if (apiBaseUrl && /^https?:\/\//.test(apiBaseUrl)) {
    const baseUrl = apiBaseUrl.replace(/\/api\/?$/, '')
    return `${baseUrl}${path}`.replace(/^http/, 'ws')
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${path}`
}

export function buildWebSocketUrl(token: string, path = DEFAULT_WS_PATH) {
  const url = new URL(getBaseWsUrl(path))
  url.searchParams.set('token', token)
  return url.toString()
}
