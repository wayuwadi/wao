import type { FileItem } from '@/types'

/** WAO 内统一优先展示用户修改后的名称；真实原文件名仍用于下载和类型判断。 */
export function fileDisplayName(file: Pick<FileItem, 'name' | 'displayName'>): string {
  return file.displayName?.trim() || file.name
}
