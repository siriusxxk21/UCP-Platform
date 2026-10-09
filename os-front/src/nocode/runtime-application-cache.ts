import { getCurrentInstance } from 'vue'
import { defineStore, getActivePinia } from 'pinia'
import type { RuntimeApplication } from '@/types/nocode/runtime'

/**
 * 两份应用定义是否等价。
 * 版本号和校验和相同还不够：服务端返回的定义按当前用户的权限裁剪过，权限变了版本不变，所以连内容一起比。
 */
export function sameRuntimeApplication(left?: RuntimeApplication, right?: RuntimeApplication): boolean {
  if (left === right) return true
  if (!left || !right) return false
  return (
    left.versionNo === right.versionNo &&
    left.checksum === right.checksum &&
    JSON.stringify(left) === JSON.stringify(right)
  )
}

export interface RuntimeApplicationCache {
  /** 打开过的应用的定义；没有就是 undefined */
  peek: (applicationId: string) => RuntimeApplication | undefined
  /** 记下刚取到的定义。与已有的等价时保留并返回原来那一份，调用方据此判断「没变」 */
  remember: (applicationId: string, application: RuntimeApplication) => RuntimeApplication
  /** 作废一个应用的定义；不给应用就全部作废（换账号、换租户、退出） */
  forget: (applicationId?: string) => void
}

/** 应用运行定义按应用缓存：再次进入先用缓存画出来，定义在后台重取后比对，不挡在数据请求前面。 */
const useStore = defineStore('nocodeRuntimeApplications', (): RuntimeApplicationCache => {
  const entries = new Map<string, RuntimeApplication>()
  return {
    peek: applicationId => entries.get(applicationId),
    remember(applicationId, application) {
      const known = entries.get(applicationId)
      if (known && sameRuntimeApplication(known, application)) return known
      entries.set(applicationId, application)
      return application
    },
    forget(applicationId) {
      if (applicationId === undefined) entries.clear()
      else entries.delete(applicationId)
    }
  }
})

/** 当前应用装了 Pinia 没有。独立夹具可能没装，用到 store 的地方据此退回不依赖 store 的行为。 */
export function piniaInstalled(): boolean {
  const instance = getCurrentInstance()
  // 组件里直接看应用上有没有；pinia 自己的探测在没装时会报注入告警。
  return instance ? !!instance.appContext.config.globalProperties.$pinia : !!getActivePinia()
}

// 没装 Pinia 时不缓存，每次照常取。
const uncached: RuntimeApplicationCache = {
  peek: () => undefined,
  remember: (_applicationId, application) => application,
  forget: () => undefined
}

export function useRuntimeApplicationCache(): RuntimeApplicationCache {
  return piniaInstalled() ? useStore() : uncached
}
