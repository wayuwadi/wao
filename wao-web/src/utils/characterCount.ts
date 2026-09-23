/// <reference lib="es2022.intl" />

const segmenter = typeof Intl.Segmenter === 'function'
  ? new Intl.Segmenter('zh-CN', { granularity: 'grapheme' }) : null

/** 统计正文非空白字形；旧浏览器回退到 Unicode 码点计数。 */
export function countCharacters(text: string): number {
  const characters = segmenter
    ? Array.from(segmenter.segment(text), ({ segment }) => segment)
    : Array.from(text)
  return characters.filter(character => /\S/u.test(character)).length
}
