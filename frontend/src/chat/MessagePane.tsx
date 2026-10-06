import type { RefObject } from 'react'
import { Composer, type Draft } from '../Composer'
import { MessageItem } from '../MessageItem'
import type { Message, User } from '../types'

interface Props {
  messages: Message[]
  user: User
  members: User[]
  /** 一覧の末尾の目印。新着時に、末尾付近を見ていればここまでスクロールする */
  listEnd: RefObject<HTMLDivElement | null>
  /** 末尾付近(80px以内)を見ているかが変わったとき */
  onNearBottomChange: (nearBottom: boolean) => void
  /** チャンネルが変わったら入力欄をリセットするためのキー */
  composerKey: number | null
  placeholder: string
  onChanged: () => void
  onOpenThread: (id: number) => void
  onError: (message: string) => void
  onSend: (d: Draft) => Promise<void>
}

export function MessagePane(p: Props) {
  const { listEnd } = p
  return (
    <>
      <div
        className="messages"
        onScroll={(e) => {
          const el = e.currentTarget
          p.onNearBottomChange(el.scrollHeight - el.scrollTop - el.clientHeight < 80)
        }}
      >
        {p.messages.length === 0 && <p className="muted">まだメッセージはありません。最初の投稿をしてみましょう。</p>}
        {p.messages.map((m) => (
          <MessageItem
            key={m.id}
            msg={m}
            me={p.user}
            members={p.members}
            onChanged={p.onChanged}
            onOpenThread={p.onOpenThread}
            onError={p.onError}
          />
        ))}
        <div ref={listEnd} />
      </div>
      <Composer key={p.composerKey} placeholder={p.placeholder} members={p.members} onSend={p.onSend} />
    </>
  )
}
