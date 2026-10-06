import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, uploadAvatar } from '../api'
import { Avatar } from '../Avatar'
import { Modal } from '../Modal'
import type { Overview, User } from '../types'

export function MembersModal(props: {
  overview: Overview
  wsId: number | null
  user: User
  isOwner: boolean
  onOpenDm: (userId: number) => void | Promise<void>
  onChanged: () => void | Promise<void>
  onError: (message: string) => void
  onInvite: () => void
  onClose: () => void
}) {
  const { overview, wsId, user, isOwner } = props
  return (
    <Modal title={`${overview.workspace.name} のメンバー`} onClose={props.onClose}>
      <ul className="member-list">
        {overview.members.map((m) => (
          <li key={m.id}>
            <Avatar name={m.displayName} url={m.avatarUrl} size={32} />
            <span className="grow">
              <strong>{m.displayName}</strong> <span className="muted small">@{m.username}</span>
              {m.id === overview.workspace.ownerId && <span className="owner-tag">オーナー</span>}
              {m.status && <div className="muted small">{m.status}</div>}
            </span>
            {m.id !== user.id && <button onClick={() => void props.onOpenDm(m.id)}>DM</button>}
            {isOwner && m.id !== user.id && (
              <button
                className="danger"
                onClick={async () => {
                  if (!confirm(`${m.displayName} をワークスペースから退出させますか?`)) return
                  try {
                    await api.del(`/workspaces/${wsId}/members/${m.id}`)
                    await props.onChanged()
                  } catch (e) {
                    props.onError((e as Error).message)
                  }
                }}
              >
                キック
              </button>
            )}
          </li>
        ))}
      </ul>
      {isOwner && (
        <button className="primary" onClick={props.onInvite}>
          ユーザーを招待
        </button>
      )}
    </Modal>
  )
}

export function TextModal(props: {
  title: string
  label: string
  submitLabel: string
  checkbox?: string
  onClose: () => void
  onSubmit: (value: string, checked: boolean) => Promise<void>
}) {
  const [value, setValue] = useState('')
  const [checked, setChecked] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await props.onSubmit(value.trim(), checked)
      props.onClose()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal title={props.title} onClose={props.onClose}>
      <form onSubmit={submit} className="form">
        <label>
          {props.label}
          <input value={value} onChange={(e) => setValue(e.target.value)} autoFocus maxLength={30} required />
        </label>
        {props.checkbox && (
          <label className="check">
            <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} /> {props.checkbox}
          </label>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy || !value.trim()}>
          {props.submitLabel}
        </button>
      </form>
    </Modal>
  )
}

export function ProfileModal({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: (u: User) => void }) {
  const [displayName, setDisplayName] = useState(user.displayName)
  const [status, setStatus] = useState(user.status)
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      onSaved(await api.put<User>('/me', { displayName, status }))
      onClose()
    } catch (err) {
      setError((err as Error).message)
    }
  }
  const avatar = async (f?: File) => {
    if (!f) return
    try {
      onSaved(await uploadAvatar(f))
    } catch (err) {
      setError((err as Error).message)
    }
  }
  return (
    <Modal title="プロフィール設定" onClose={onClose}>
      <form onSubmit={save} className="form">
        <div className="row">
          <Avatar name={user.displayName} url={user.avatarUrl} size={64} />
          <button type="button" onClick={() => fileRef.current?.click()}>
            アバター画像を変更
          </button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={(e) => void avatar(e.target.files?.[0])} />
        </div>
        <label>
          表示名
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={30} required />
        </label>
        <label>
          ステータス
          <input value={status} onChange={(e) => setStatus(e.target.value)} maxLength={100} placeholder="例: 会議中 / 休暇中" />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary">保存</button>
      </form>
    </Modal>
  )
}

export function ChannelMembersModal(props: { channelId: number; title: string; onInvite: () => void; onClose: () => void }) {
  const [list, setList] = useState<User[]>([])
  useEffect(() => {
    void api.get<User[]>(`/channels/${props.channelId}/members`).then(setList)
  }, [props.channelId])
  return (
    <Modal title={props.title} onClose={props.onClose}>
      <ul className="member-list">
        {list.map((m) => (
          <li key={m.id}>
            <Avatar name={m.displayName} url={m.avatarUrl} size={32} />
            <span className="grow">
              <strong>{m.displayName}</strong> <span className="muted small">@{m.username}</span>
            </span>
          </li>
        ))}
      </ul>
      <button className="primary" onClick={props.onInvite}>
        メンバーを招待
      </button>
    </Modal>
  )
}
