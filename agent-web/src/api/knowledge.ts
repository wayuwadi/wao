import { http, unwrap } from './http'
import type { ArticleBlock } from '@/articleExport'

export interface KnowledgeFolder {
  id: string
  name: string
  docs: string[]
}

export interface KnowledgeBaseOption {
  id: string
  name: string
  folders: KnowledgeFolder[]
}

export interface CreatedKnowledgeDocument {
  id: string
  kbId: string
  folderId: string
  title: string
}

export const listKnowledgeBases = () => unwrap<KnowledgeBaseOption[]>(http.get('/api/kbs'))

export const createKnowledgeArticle = (payload: {
  kbId: string
  folderId: string
  title: string
  blocks: ArticleBlock[]
}) => unwrap<CreatedKnowledgeDocument>(http.post('/api/docs', payload))
