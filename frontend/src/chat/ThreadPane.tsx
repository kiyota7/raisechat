import type { ThreadData } from '../api'
import { Composer, type Draft } from '../Composer'
import { MessageItem } from '../MessageItem'
import type { User } from '../types'

interface Props {
  thread: ThreadData | null
  threadId: number
  user: User
  members: User[]
  onClose: () => void
  onChanged: () => void
  onError: (message: string) => void
  onSend: (d: Draft, parentId: number) => Promise<void>
}

export function ThreadPane({ thread, threadId, user, members, onClose, onChanged, onError, onSend }: Props) {
  return (
    <aside className="thread">
      <div className="row between">
        <h3>スレッド</h3>
        <button className="icon-btn" aria-label="スレッドを閉じる" onClick={onClose}>
          ✕
        </button>
      </div>
      {thread ? (
        <>
          <div className="messages">
            <MessageItem msg={thread.parent} me={user} members={members} inThread onChanged={onChanged} onError={onError} />
            <div className="divider muted small">{thread.replies.length}件の返信</div>
            {thread.replies.map((m) => (
              <MessageItem key={m.id} msg={m} me={user} members={members} inThread onChanged={onChanged} onError={onError} />
            ))}
          </div>
          {!thread.parent.deleted && (
            <Composer
              key={`t${threadId}`}
              placeholder="返信する"
              members={members}
              onSend={(d) => onSend(d, thread.parent.id)}
            />
          )}
        </>
      ) : (
        <p className="muted">読み込み中...</p>
      )}
    </aside>
  )
}
