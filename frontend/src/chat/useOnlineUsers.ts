import { useCallback, useEffect, useState } from 'react'
import { subscribe } from '../realtime'

/** オンラインのユーザーID。概要APIの結果で置き換え、状態の変化は購読で追従する */
export function useOnlineUsers() {
  const [onlineIds, setOnlineIds] = useState<Set<number>>(new Set())

  // オンライン状態の変化(同じワークスペースのメンバーのみ届く)
  useEffect(
    () =>
      subscribe('/user/queue/presence', (b) => {
        setOnlineIds((cur) => {
          const next = new Set(cur)
          if (b.online) next.add(b.userId)
          else next.delete(b.userId)
          return next
        })
      }),
    [],
  )

  const replaceOnline = useCallback((ids: number[]) => setOnlineIds(new Set(ids)), [])
  return { onlineIds, replaceOnline }
}
