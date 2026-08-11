import { http, unwrap } from './http'
import type { AgentSession } from '@/types'

export const getSession = () => unwrap<AgentSession>(http.get('/api/agent/session'))
