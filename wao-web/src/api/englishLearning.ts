import { http, type ApiResponse } from './http'

export interface EnglishLearningProfile {
  id: string
  goal: string
  dailyTargetMinutes: number
  startDate: string
  updatedAt: string
}

export interface EnglishLearningSession {
  id: string
  date: string
  lessonId: string
  listeningDone: boolean
  speakingDone: boolean
  readingDone: boolean
  writingDone: boolean
  practiceMinutes: number
  confidence: number | null
  writingText: string
  reflection: string
  nextFocus: string
  completedAt: string | null
  updatedAt: string
}

export interface EnglishLearningOverview {
  profile: EnglishLearningProfile | null
  selectedDate: string
  session: EnglishLearningSession | null
  completedDays: number
  currentStreak: number
  totalMinutes: number
  recentSessions: EnglishLearningSession[]
}

export interface SaveEnglishLearningSession {
  lessonId: string
  listeningDone: boolean
  speakingDone: boolean
  readingDone: boolean
  writingDone: boolean
  practiceMinutes: number
  confidence: number | null
  writingText: string
  reflection: string
  nextFocus: string
  completed: boolean
}

const data = <T>(request: Promise<{ data: ApiResponse<T> }>) =>
  request.then((response) => response.data.data)

export const fetchEnglishLearning = (date?: string) =>
  data(http.get<ApiResponse<EnglishLearningOverview>>('/api/english-learning', { params: { date } }))

export const saveEnglishLearningProfile = (body: {
  goal: string
  dailyTargetMinutes: number
  startDate: string
}) => data(http.put<ApiResponse<EnglishLearningProfile>>('/api/english-learning/profile', body))

export const saveEnglishLearningSession = (date: string, body: SaveEnglishLearningSession) =>
  data(http.put<ApiResponse<EnglishLearningSession>>(`/api/english-learning/sessions/${date}`, body))
