import identity from './brandIdentity.json'

export const brandWordmark = identity.wordmark
// 公共品牌资源使用运行时主站路径，避免 Vite 的版本 base 改写到仅开放 assets 的目录。
export const brandMonoMask = 'url("/brand/wao-soft-loop-mono-v4.svg")'
