import axios from 'axios'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export const http = axios.create({
  baseURL: '/',
  timeout: 10_000,
  headers: {
    Accept: 'application/json',
  },
})

http.interceptors.response.use((response) => {
  const envelope = response.data as ApiResponse<unknown>
  if (typeof envelope?.code === 'number' && envelope.code !== 0) {
    return Promise.reject(new Error(envelope.message || '请求失败'))
  }
  return response
})
