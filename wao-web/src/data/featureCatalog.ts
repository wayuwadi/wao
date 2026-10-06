import type { ReleaseEdition } from '@/types/featureExplorer'

/** 按可靠发布区间生成的说明；边界不可验证时仅说明 HEAD。 */
export const CURRENT_RELEASE = __WAO_BUILD_RELEASE__
/** 保留已确认的历史记录；功能目录只在 navigationCatalog.ts 维护。 */
export const RELEASE_HISTORY = [CURRENT_RELEASE, ...(__WAO_RELEASE_HISTORY__ as ReleaseEdition[]).filter(item => item.id !== CURRENT_RELEASE.id)]
