import { http, type ApiResponse } from './http'

export interface HomeDateNotice {
  key: string
  kind: 'IMPORTANT' | 'BIRTHDAY' | 'ANNIVERSARY' | 'SOLAR_TERM'
  subject: 'SELF' | 'FAMILY' | 'OTHER'
  name: string
  date: string
  daysBefore: number
  title: string
  detail: string
  targetPath: string
  priority: number
}
export interface HomeDateQueue {
  date: string
  notices: HomeDateNotice[]
}
export interface HomeDateReservation extends HomeDateQueue {
  token: string
}
export interface HomeDateSettings {
  enabled: boolean
  solarTerms: boolean
  revision: number
}
export async function fetchHomeDateReminders(): Promise<HomeDateQueue> {
  return (await http.get<ApiResponse<HomeDateQueue>>('/api/home/date-reminders')).data.data
}
export async function reserveHomeDateReminders(queue: HomeDateQueue): Promise<HomeDateReservation> {
  return (
    await http.post<ApiResponse<HomeDateReservation>>('/api/home/date-reminders/reserve', {
      date: queue.date,
      keys: queue.notices.slice(0, 8).map((item) => item.key),
    })
  ).data.data
}
export async function recordHomeDateReminder(
  value: HomeDateReservation,
  state: 'SHOWN' | 'CONFIRMED' | 'RELEASED',
): Promise<void> {
  await http.post('/api/home/date-reminders/receipt', {
    date: value.date,
    keys: value.notices.map((item) => item.key),
    token: value.token,
    state,
  })
}
export async function fetchHomeDateSettings(): Promise<HomeDateSettings> {
  return (await http.get<ApiResponse<HomeDateSettings>>('/api/home/date-reminders/settings')).data
    .data
}
export async function saveHomeDateSettings(value: HomeDateSettings): Promise<HomeDateSettings> {
  const saved = (
    await http.put<ApiResponse<HomeDateSettings>>('/api/home/date-reminders/settings', value)
  ).data.data
  window.dispatchEvent(new Event('wao-home-date-changed'))
  return saved
}
