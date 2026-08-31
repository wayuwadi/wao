import { http, type ApiResponse } from './http'

export type CrawlerLanguage = 'PYTHON' | 'JAVASCRIPT'

export interface CrawlerCapability {
  id: string
  name: string
  description: string
  language: CrawlerLanguage
  sourceCode: string
  enabled: boolean
  revision: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface SaveCrawlerCapability {
  name: string
  description: string
  language: CrawlerLanguage
  sourceCode: string
  enabled: boolean
  revision: number | null
}

const data = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data

export const fetchCrawlerCapabilities = () =>
  data<CrawlerCapability[]>(http.get('/api/crawler-capabilities'))

export const createCrawlerCapability = (body: SaveCrawlerCapability) =>
  data<CrawlerCapability>(http.post('/api/crawler-capabilities', body))

export const updateCrawlerCapability = (id: string, body: SaveCrawlerCapability) =>
  data<CrawlerCapability>(http.put(`/api/crawler-capabilities/${id}`, body))

export const deleteCrawlerCapability = (id: string, revision: number) =>
  data<null>(http.delete(`/api/crawler-capabilities/${id}`, { params: { revision } }))
