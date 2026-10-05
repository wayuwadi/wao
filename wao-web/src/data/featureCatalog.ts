import type { ReleaseEdition } from '@/types/featureExplorer'
import releaseHistory from './releaseHistory.json'

/** 构建 HEAD 自动生成的说明；随版本变化，不依赖人工精选。 */
export const CURRENT_RELEASE = __WAO_BUILD_RELEASE__
/** 保留已确认的历史记录；功能目录只在 navigationCatalog.ts 维护。 */
export const RELEASE_HISTORY = [CURRENT_RELEASE, ...(releaseHistory as ReleaseEdition[]).filter(item => item.id !== CURRENT_RELEASE.id)]
