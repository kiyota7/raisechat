import { useRef, useState, type KeyboardEvent } from 'react'
import { uploadFile } from './api'
import type { User } from './types'

export interface Draft {
  content: string
  attachmentUrl?: string
  attachmentType?: 'image' | 'video'
}

interface Props {
  placeholder: string
  members: User[]
  onSend: (d: Draft) => Promise<void>
  /** 入力中に呼ばれる(呼び出し側で通知する)。連続入力では一定間隔にまとめて呼ぶ */
  onTyping?: () => void
}

const TYPING_INTERVAL_MS = 2000

export function Composer({ placeholder, members, onSend, onTyping }: Props) {
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [cursor, setCursor] = useState(0)
  const area = useRef<HTMLTextAreaElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const lastTyping = useRef(0)

  const notifyTyping = (value: string) => {
    if (!onTyping || !value.trim()) return
    const now = Date.now()
    if (now - lastTyping.current < TYPING_INTERVAL_MS) return
    lastTyping.current = now
    onTyping()
  }

  const before = text.slice(0, cursor)
  const m = /(^|\s)@([A-Za-z0-9_]*)$/.exec(before)
  const suggestions = m
    ? members
        .filter((u) => u.username.toLowerCase().startsWith(m[2].toLowerCase()) || u.displayName.startsWith(m[2]))
        .slice(0, 5)
    : []

  const insertMention = (u: User) => {
    if (!m) return
    const start = before.length - m[2].length - 1
    const next = `${text.slice(0, start)}@${u.username} ${text.slice(cursor)}`
    setText(next)
    const pos = start + u.username.length + 2
    setCursor(pos)
    requestAnimationFrame(() => {
      area.current?.focus()
      area.current?.setSelectionRange(pos, pos)
    })
  }

  const send = async () => {
    if (busy || (!text.trim() && !file)) return
    setBusy(true)
    setError('')
    try {
      let attach: Pick<Draft, 'attachmentUrl' | 'attachmentType'> = {}
      if (file) {
        const up = await uploadFile(file)
        attach = { attachmentUrl: up.url, attachmentType: up.type }
      }
      await onSend({ content: text, ...attach })
      setText('')
      setFile(null)
      if (fileInput.current) fileInput.current.value = ''
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // 日本語入力の変換確定中のEnterでは送信しない
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void send()
    }
  }

  return (
    <div className="composer">
      {suggestions.length > 0 && (
        <ul className="suggest" role="listbox">
          {suggestions.map((u) => (
            <li key={u.id} role="option" onMouseDown={(e) => { e.preventDefault(); insertMention(u) }}>
              @{u.username} <span className="muted">{u.displayName}</span>
            </li>
          ))}
        </ul>
      )}
      {file && (
        <div className="attach-chip">
          📎 {file.name}
          <button type="button" className="icon-btn" aria-label="添付を外す" onClick={() => setFile(null)}>
            ✕
          </button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="composer-row">
        <button type="button" className="icon-btn" aria-label="ファイルを添付" onClick={() => fileInput.current?.click()}>
          📎
        </button>
        <input
          ref={fileInput}
          type="file"
          accept="image/*,video/*"
          hidden
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        <textarea
          ref={area}
          value={text}
          rows={2}
          placeholder={placeholder}
          onChange={(e) => {
            setText(e.target.value)
            setCursor(e.target.selectionStart)
            notifyTyping(e.target.value)
          }}
          onSelect={(e) => setCursor(e.currentTarget.selectionStart)}
          onKeyDown={onKeyDown}
        />
        <button type="button" className="primary" disabled={busy || (!text.trim() && !file)} onClick={() => void send()}>
          送信
        </button>
      </div>
      <p className="hint">Enterで送信 / Shift+Enterで改行 / マークダウン記法が使えます</p>
    </div>
  )
}
