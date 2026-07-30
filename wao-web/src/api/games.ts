import { http, type ApiResponse } from './http'

export type MoveDirection = 'UP' | 'DOWN' | 'LEFT' | 'RIGHT'

export interface GameRunRecord {
  id: string
  score: number
  highestTile: number
  moves: number
  outcome: 'WON' | 'GAME_OVER' | 'RESTARTED'
  startedAt: string
  endedAt: string
}

export interface NumberMergeStats {
  bestScore: number
  highestTile: number
  gamesPlayed: number
  totalMoves: number
  wins: number
}

export interface NumberMergeGame {
  board: number[]
  score: number
  moves: number
  won: boolean
  gameOver: boolean
  canUndo: boolean
  revision: number
  startedAt: string
  updatedAt: string
  stats: NumberMergeStats
  recentRuns: GameRunRecord[]
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchNumberMergeGame = () =>
  data(http.get<ApiResponse<NumberMergeGame>>('/api/games/number-merge'))

export const moveNumberMergeGame = (direction: MoveDirection, revision: number) =>
  data(http.post<ApiResponse<NumberMergeGame>>('/api/games/number-merge/move', { direction, revision }))

export const undoNumberMergeGame = (revision: number) =>
  data(http.post<ApiResponse<NumberMergeGame>>('/api/games/number-merge/undo', { revision }))

export const restartNumberMergeGame = (revision: number) =>
  data(http.post<ApiResponse<NumberMergeGame>>('/api/games/number-merge/restart', { revision }))
