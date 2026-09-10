import { mockChangelogEntries } from '@/mocks/changelog'
import type { ChangelogSource } from '@/types/changelog'

/** V1 明确使用示例源；未来替换为 HTTP adapter，不以 mock 兜底接口失败。 */
export const changelogSource: ChangelogSource = {
  async list() {
    return structuredClone(mockChangelogEntries)
  },
}

export const fetchChangelog = () => changelogSource.list()
