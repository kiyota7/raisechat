import { useState } from 'react'
import { api } from './api'
import { Avatar } from './Avatar'
import { formatTime } from './format'
import { Markdown } from './Markdown'
import type { Message, User } from './types'

const EMOJIS = ['👍', '❤️', '😂', '🎉', '🙏', '👀', '🔥', '✅']

interface Props {
  msg: Message
  me: User
  members: User[]
  /** オンラインのユーザーID。未指定ならオンライン表示をしない */
  onlineIds?: Set<number>
  inThread?: boolean
  onChanged: () => void
  onOpenThread?: (id: number) => void
  onError: (m: string) => void
}

export function MessageItem({ msg, me, members, onlineIds, inThread, onChanged, onOpenThread, onError }: Props) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(msg.content)
  const [picker, setPicker] = useState(false)

  const run = async (fn: () => Promise<unknown>) => {
    try {
      await fn()
      onChanged()
    } catch (e) {
      onError((e as Error).message)
    }
  }

  const react = (emoji: string) => {
    setPicker(false)
    void run(() => api.post(`/messages/${msg.id}/reactions`, { emoji }))
  }

  const saveEdit = async () => {
    if (!draft.trim()) return
    await run(() => api.put(`/messages/${msg.id}`, { content: draft }))
    setEditing(false)
  }

  if (msg.deleted) {
    return (
      <div className="msg">
        <div className="msg-body muted deleted">このメッセージは削除されました</div>
        {!inThread && msg.replyCount > 0 && (
          <button className="link" onClick={() => onOpenThread?.(msg.id)}>
            {msg.replyCount}件の返信
          </button>
        )}
      </div>
    )
  }

  const mine = msg.userId === me.id
  return (
    <div className="msg" data-testid="message">
      <Avatar name={msg.displayName} url={msg.avatarUrl} online={onlineIds?.has(msg.userId)} />
      <div className="msg-main">
        <div className="msg-head">
          <strong>{msg.displayName}</strong>
          <span className="muted small">@{msg.username}</span>
          <span className="muted small">{formatTime(msg.createdAt)}</span>
          {msg.editedAt && <span className="muted small">(編集済み)</span>}
        </div>
        {editing ? (
          <div className="edit-box">
            <textarea value={draft} onChange={(e) => setDraft(e.target.value)} rows={3} />
            <div className="row">
              <button className="primary" onClick={() => void saveEdit()}>保存</button>
              <button onClick={() => { setEditing(false); setDraft(msg.content) }}>キャンセル</button>
            </div>
          </div>
        ) : (
          <>
            {msg.content && <Markdown text={msg.content} members={members} selfId={me.id} />}
            {msg.attachmentUrl && msg.attachmentType === 'image' && (
              <a href={msg.attachmentUrl} target="_blank" rel="noopener noreferrer">
                <img className="attachment" src={msg.attachmentUrl} alt="添付画像" />
              </a>
            )}
            {msg.attachmentUrl && msg.attachmentType === 'video' && (
              <video className="attachment" src={msg.attachmentUrl} controls />
            )}
          </>
        )}
        {msg.reactions.length > 0 && (
          <div className="reactions">
            {msg.reactions.map((r) => (
              <button
                key={r.emoji}
                className={`chip${r.userIds.includes(me.id) ? ' chip-on' : ''}`}
                onClick={() => react(r.emoji)}
              >
                {r.emoji} {r.userIds.length}
              </button>
            ))}
          </div>
        )}
        {!inThread && msg.replyCount > 0 && (
          <button className="link" onClick={() => onOpenThread?.(msg.id)}>
            💬 {msg.replyCount}件の返信
          </button>
        )}
      </div>
      {!editing && (
        <div className="msg-actions">
          <button className="icon-btn" aria-label="リアクション" title="リアクション" onClick={() => setPicker(!picker)}>
            😀
          </button>
          {!inThread && (
            <button className="icon-btn" aria-label="スレッドで返信" title="スレッドで返信" onClick={() => onOpenThread?.(msg.id)}>
              💬
            </button>
          )}
          {mine && (
            <>
              <button className="icon-btn" aria-label="編集" title="編集" onClick={() => setEditing(true)}>
                ✏️
              </button>
              <button
                className="icon-btn"
                aria-label="削除"
                title="削除"
                onClick={() => {
                  if (confirm('このメッセージを削除しますか?')) void run(() => api.del(`/messages/${msg.id}`))
                }}
              >
                🗑️
              </button>
            </>
          )}
          {picker && (
            <div className="picker">
              {EMOJIS.map((e) => (
                <button key={e} className="icon-btn" onClick={() => react(e)}>
                  {e}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
