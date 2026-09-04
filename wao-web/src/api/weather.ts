import { http, type ApiResponse } from './http'
export interface WeatherLocation { name: string; latitude: number; longitude: number }
export interface WeatherDay { date: string; code: number | null; low: number | null; high: number | null; rain: number | null; wind: number | null }
export interface WeatherForecast { fetchedAt: string; days: WeatherDay[] }
export async function searchWeatherLocations(query: string) {
  return (await http.get<ApiResponse<WeatherLocation[]>>('/api/weather/locations', { params: { query } })).data.data
}
export async function fetchWeather(location: WeatherLocation) {
  return (await http.get<ApiResponse<WeatherForecast>>('/api/weather/forecast', { params: { latitude: location.latitude, longitude: location.longitude } })).data.data
}
