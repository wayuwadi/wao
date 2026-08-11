import axios from 'axios'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

export const http = axios.create({
  baseURL: '/',
  timeout: 10_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

http.interceptors.response.use((response) => {
  const envelope = response.data as ApiResponse<unknown>
  if (typeof envelope?.code === 'number' && envelope.code !== 0) {
    return Promise.reject(new ApiError(envelope.message || '请求失败', response.status))
  }
  return response
}, (error: unknown) => {
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    return Promise.reject(new ApiError(error.response?.data?.message || error.message, error.response?.status))
  }
  return Promise.reject(error)
})

export async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await promise).data.data
}
