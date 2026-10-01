import { useEffect, useState } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from './api'
import { Chat } from './Chat'
import { Login } from './Login'
import type { User } from './types'

export default function App() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(!!getToken())

  const logout = () => {
    setToken(null)
    setUser(null)
  }

  useEffect(() => {
    setUnauthorizedHandler(logout)
    if (!getToken()) return
    api
      .get<User>('/me')
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="center-screen">読み込み中...</div>
  if (!user) return <Login onLogin={setUser} />
  return <Chat user={user} onUserChange={setUser} onLogout={logout} />
}
