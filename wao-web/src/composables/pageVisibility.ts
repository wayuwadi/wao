import { inject, ref, type InjectionKey, type Ref } from 'vue'

/** 保留编辑器的页签可隐藏但不卸载；后台同步应同时考虑工作区与浏览器可见性。 */
export const pageVisibleKey: InjectionKey<Readonly<Ref<boolean>>> = Symbol('wao-page-visible')

export function usePageVisibility(): Readonly<Ref<boolean>> {
  return inject(pageVisibleKey, ref(true))
}
