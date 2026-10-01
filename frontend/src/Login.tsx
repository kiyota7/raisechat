import { useState, type FormEvent } from 'react'
import { api, setToken, type Session } from './api'
import type { User } from './types'

export function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const body =
        mode === 'login' ? { username, password } : { username, password, displayName }
      const s = await api.post<Session>(`/auth/${mode}`, body)
      setToken(s.token)
      onLogin(s.user)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="center-screen">
      <form className="card auth-card" onSubmit={submit}>
        <h1 className="logo">RaiseChat</h1>
        <p className="muted">{mode === 'login' ? 'ログイン' : '新規登録'}</p>
        <label>
          ユーザーID
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            placeholder="半角英数字とアンダースコア(3〜20文字)"
            required
          />
        </label>
        {mode === 'register' && (
          <label>
            表示名(任意)
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={30} />
          </label>
        )}
        <label>
          パスワード
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            placeholder={mode === 'register' ? '8文字以上' : ''}
            required
          />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy}>
          {mode === 'login' ? 'ログイン' : '登録してはじめる'}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError('')
          }}
        >
          {mode === 'login' ? 'アカウントをお持ちでない方はこちら' : 'ログインはこちら'}
        </button>
      </form>
    </div>
  )
}
