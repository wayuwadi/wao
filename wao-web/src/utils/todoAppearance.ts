export const TODO_PALETTES = [
  { id: 'default', name: '默认' },
  { id: 'blue', name: '晴蓝' },
  { id: 'turquoise', name: '松石' },
  { id: 'mist', name: '秘青' },
  { id: 'sage', name: '蒹葭' },
  { id: 'apricot', name: '杏黄' },
  { id: 'pink', name: '桃夭' },
  { id: 'purple', name: '暮山紫' },
  { id: 'brown', name: '沉香' },
  { id: 'navy', name: '藏蓝' },
  { id: 'gray', name: '灰色' },
  { id: 'midnight', name: '暗夜' },
] as const

export type TodoPalette = typeof TODO_PALETTES[number]['id']

export function decodeTodoPalette(raw: string | null): TodoPalette {
  return TODO_PALETTES.find(palette => palette.id === raw)?.id ?? 'turquoise'
}
