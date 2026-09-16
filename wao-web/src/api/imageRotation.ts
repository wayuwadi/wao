import { http } from './http'

/** Read an existing image without forwarding the WAO session to another origin. */
export async function loadRotationImage(src: string): Promise<Blob> {
  const url = new URL(src, window.location.origin)
  if (url.origin !== window.location.origin || !/^\/api\/images\/[^/]+\/raw$/.test(url.pathname)) {
    throw new Error('请先将外部图片上传到 WAO，再旋转保存')
  }
  const response = await http.get<Blob>(url.pathname, { responseType: 'blob' })
  if (response.data.size > 10 * 1024 * 1024) throw new Error('图片超过 10MB，请先压缩')
  return response.data
}
