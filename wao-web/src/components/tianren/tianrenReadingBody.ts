import { Marked } from 'marked'

const sectionTitles = [
  '卦象事实', '《易经》卦意（AI 概括）', '变与不变（AI 概括）', '当日状态观察（AI 延伸）',
  '《黄帝内经》生活观（AI 概括）', '今日调养提示（AI 延伸）', '今日一件小事', '留给自己的问题', '边界提醒',
]
const parser = new Marked()

/** 仅替换完整受控九章节的首节；旧版或异常正文完整保留。 */
export function tianrenReadingBody(markdown: string): string {
  const tokens = parser.lexer(markdown)
  const meaningful = tokens.filter(token => token.type !== 'space')
  const headings = tokens.filter(token => token.type === 'heading')
  if (meaningful[0]?.type !== 'heading' || headings.length !== sectionTitles.length
    || headings.some((token, index) => token.type !== 'heading' || token.depth !== 2 || token.text !== sectionTitles[index])) return markdown
  const secondHeading = tokens.indexOf(headings[1]!)
  return tokens.slice(secondHeading).map(token => token.raw).join('')
}
