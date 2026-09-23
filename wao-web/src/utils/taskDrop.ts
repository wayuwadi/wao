/** 行上半区插入到前方，下半区插入到后方；没有隐含的嵌套区。 */
export function taskDropAfter(event: DragEvent): boolean {
  const box = (event.currentTarget as HTMLElement).getBoundingClientRect()
  return box.height > 0 && event.clientY > box.top + box.height / 2
}
