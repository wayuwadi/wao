import { http, type ApiResponse } from './http'

export type FoodDayType = 'WORKDAY' | 'RESTDAY'
export type MealType = 'BREAKFAST' | 'LUNCH' | 'DINNER'
export type MealComfort = 'COMFORTABLE' | 'NEUTRAL' | 'UNCOMFORTABLE'
export type FoodTaskType = 'GROCERY' | 'PREP'
export type FoodTaskStatus = 'OPEN' | 'DONE'

export interface FoodMealTemplate {
  id: string
  dayType: FoodDayType
  mealType: MealType
  title: string
  targetTime: string
  foods: string[]
  prepMinutes: number
  note: string
  enabled: boolean
}

export interface FoodMealLog {
  id: string
  date: string
  dayType: FoodDayType
  mealType: MealType
  eatenAt: string
  foods: string[]
  prepMinutes: number
  comfort: MealComfort
  qualityScore: number
  observation: string
}

export interface FoodPreparationTask {
  id: string
  type: FoodTaskType
  title: string
  dueDate: string
  status: FoodTaskStatus
  note: string
}

export interface FoodProfile {
  preferences: string
  avoidances: string
  stomachObservations: string
  efficiencyGoal: string
}

export interface FoodWorkspace {
  templates: FoodMealTemplate[]
  mealLogs: FoodMealLog[]
  preparationTasks: FoodPreparationTask[]
  profile: FoodProfile
  principles: string
  updatedAt: string | null
}

const data = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data

export const fetchFoodWorkspace = () => data<FoodWorkspace>(http.get('/api/food'))
export const saveFoodWorkspace = (body: FoodWorkspace) =>
  data<FoodWorkspace>(http.put('/api/food', body))
