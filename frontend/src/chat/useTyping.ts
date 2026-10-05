import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribe } from '../realtime'

/** 最後の入力中通知からこの時間だけ「入力中」を表示する */
const TYPING_SHOW_MS = 3500

/** 表示中のチャンネルで入力中のユーザーID。最後の通知から一定時間で消す(自分の分は含めない) */
export function useTyping(channelId: number | null, canRead: boolean, selfId: number) {
  const [typingIds, setTypingIds] = useState<number[]>([])
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>())

  /** 投稿された時点など、そのユーザーの「入力中」をすぐに消す */
  const clearTyping = useCallback((uid: number) => {
    clearTimeout(timers.current.get(uid))
    timers.current.delete(uid)
    setTypingIds((cur) => cur.filter((x) => x !== uid))
  }, [])

  useEffect(() => {
    if (!channelId || !canRead) return
    const t = timers.current
    const stop = subscribe(`/topic/channels/${channelId}/typing`, (ev) => {
      const uid = ev.userId as number
      if (uid === selfId) return
      clearTimeout(t.get(uid))
      t.set(
        uid,
        setTimeout(() => {
          t.delete(uid)
          setTypingIds((cur) => cur.filter((x) => x !== uid))
        }, TYPING_SHOW_MS),
      )
      setTypingIds((cur) => (cur.includes(uid) ? cur : [...cur, uid]))
    })
    return () => {
      stop()
      t.forEach(clearTimeout)
      t.clear()
      setTypingIds([])
    }
  }, [channelId, canRead, selfId])

  return { typingIds, clearTyping }
}
