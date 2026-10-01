import type { Message, User } from './types'

const TOKEN_KEY = 'raisechat_token'

export const getToken = () => localStorage.getItem(TOKEN_KEY)
export const setToken = (t: string | null) =>
  t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY)

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(`/api${path}`, { method, headers, body: payload })
  if (!res.ok) {
    let message = 'エラーが発生しました'
    try {
      message = (await res.json()).message ?? message
    } catch {
      /* ignore */
    }
    if (res.status === 401 && token) onUnauthorized()
    throw new ApiError(res.status, message)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T = void>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: (path: string) => request<void>('DELETE', path),
}

export interface Session {
  token: string
  user: User
}

export async function uploadFile(file: File): Promise<{ url: string; type: 'image' | 'video' }> {
  const fd = new FormData()
  fd.append('file', file)
  return request('POST', '/files', fd)
}

export async function uploadAvatar(file: File): Promise<User> {
  const fd = new FormData()
  fd.append('file', file)
  return request('POST', '/me/avatar', fd)
}

export type ThreadData = { parent: Message; replies: Message[] }
