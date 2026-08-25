/**
 * 快捷链接配置：集中管理系统中需要频繁访问的外部服务入口。
 *
 * 在侧边栏、命令面板等处复用，新增链接只需在此追加一项。
 */

export interface QuickLink {
  id: string
  label: string
  subtitle: string
  icon: string
  url: string
  /** 命令面板模糊搜索用的额外关键词（拼音 / 英文同义词）。 */
  keywords: string[]
}

/** GitHub 仓库快捷入口。 */
export const GITHUB_REPOS: QuickLink[] = [
  {
    id: 'gh-wao',
    label: 'WAO 主仓库',
    subtitle: 'wayuwacn/wao',
    icon: 'ri-git-repository-line',
    url: 'https://github.com/wayuwacn/wao',
    keywords: ['github', 'cangku', 'repo', 'wao'],
  },
  {
    id: 'gh-wao-agent',
    label: 'WAO Agent 仓库',
    subtitle: 'wayuwacn/wao-agent',
    icon: 'ri-git-repository-line',
    url: 'https://github.com/wayuwacn/wao-agent',
    keywords: ['github', 'agent', 'cangku', 'repo'],
  },
]

/** 运维 / 基础设施快捷入口。 */
export const DEVOPS_LINKS: QuickLink[] = [
  {
    id: 'gh-wao-prs',
    label: 'WAO Pull Requests',
    subtitle: '查看待合并的代码变更',
    icon: 'ri-git-pull-request-line',
    url: 'https://github.com/wayuwacn/wao/pulls',
    keywords: ['pr', 'pull request', 'hebing', 'daima'],
  },
  {
    id: 'gh-wao-issues',
    label: 'WAO Issues',
    subtitle: '缺陷跟踪与需求管理',
    icon: 'ri-error-warning-line',
    url: 'https://github.com/wayuwacn/wao/issues',
    keywords: ['issue', 'bug', 'xuqiu', 'wenti'],
  },
  {
    id: 'gh-wao-actions',
    label: 'GitHub Actions',
    subtitle: '桌面端与 iOS 原生构建',
    icon: 'ri-flashlight-line',
    url: 'https://github.com/wayuwacn/wao/actions',
    keywords: ['actions', 'desktop', 'ios', 'goujian'],
  },
]

/** WAO 自身的独立产品入口。 */
export const PRODUCT_LINKS: QuickLink[] = [
  {
    id: 'wao-desktop-pet',
    label: 'WAO 桌面宠物',
    subtitle: '下载 Windows / macOS 版本',
    icon: 'ri-bear-smile-line',
    url: '/desktop',
    keywords: ['desktop', 'pet', 'windows', 'macos', 'zhuomian', 'chongwu', 'xiazai'],
  },
]

/** 全部快捷链接（供命令面板遍历）。 */
export const ALL_QUICK_LINKS: QuickLink[] = [...PRODUCT_LINKS, ...GITHUB_REPOS, ...DEVOPS_LINKS]

/**
 * 从 Git 仓库 URL 推导 GitHub 浏览地址。
 *
 * 支持格式：
 * - `https://github.com/org/repo.git`
 * - `git@github.com:org/repo.git`
 * - `https://github.com/org/repo`（已规范）
 *
 * 返回空串表示无法识别。
 */
export function githubBrowseUrl(repoUrl: string): string {
  if (!repoUrl) return ''
  // HTTPS 格式
  const httpsMatch = repoUrl.match(/https?:\/\/github\.com\/([^/]+\/[^/]+?)(?:\.git)?\/?$/)
  if (httpsMatch) return `https://github.com/${httpsMatch[1]}`
  // SSH 格式
  const sshMatch = repoUrl.match(/git@github\.com:([^/]+\/[^/]+?)(?:\.git)?\/?$/)
  if (sshMatch) return `https://github.com/${sshMatch[1]}`
  return ''
}

/**
 * 由 GitHub 仓库 URL 拼接特定子页面。
 *
 * @param repoUrl  原始仓库地址
 * @param suffix   子路径，如 `pulls`、`issues`、`actions`、`tree/main`
 */
export function githubSubPage(repoUrl: string, suffix: string): string {
  const base = githubBrowseUrl(repoUrl)
  if (!base) return ''
  return `${base}/${suffix}`
}
