import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ConnectionStatus } from '@/realtime/events'

/** 保存需要被晚挂载组件立即读取的实时通信状态。 */
export const useRealtimeStore = defineStore('realtime', () => {
  const connectionStatus = ref<ConnectionStatus>('disconnected')
  const unreadCount = ref(0)

  function setConnectionStatus(status: ConnectionStatus): void {
    connectionStatus.value = status
  }

  function setUnreadCount(count: number): void {
    unreadCount.value = Math.max(0, count)
  }

  function reset(): void {
    connectionStatus.value = 'disconnected'
    unreadCount.value = 0
  }

  return { connectionStatus, unreadCount, setConnectionStatus, setUnreadCount, reset }
})
