import { useEffect, useRef } from 'react'
import { onReconnect, startRealtime, stopRealtime, subscribe } from '../realtime'

interface Args {
  wsId: number | null
  channelId: number | null
  canRead: boolean
  threadId: number | null
  loadOverview: () => Promise<void>
  loadMessages: () => Promise<void>
  loadThread: () => Promise<void>
  /** 見ているチャンネルが削除されたとき(選択を外す。サイドバーの取り直しはこのフックが行う) */
  onChannelDeleted: () => void
}

/**
 * リアルタイム更新(STOMP)。イベントは再取得の合図で、データは従来のRESTで取り直す。
 * 接続の開始・終了、購読、再接続時の取り直しをまとめて扱う。
 */
export function useChatRealtime({ wsId, channelId, canRead, threadId, loadOverview, loadMessages, loadThread, onChannelDeleted }: Args) {
  useEffect(() => {
    startRealtime()
    return stopRealtime
  }, [])

  // 購読のコールバックから、最新のスレッド情報を読むための参照
  const live = useRef({ threadId, loadThread })
  useEffect(() => {
    live.current = { threadId, loadThread }
  })

  useEffect(() => {
    if (!wsId) return
    return subscribe('/user/queue/overview', (b) => {
      if (b.workspaceId !== wsId) return
      void loadOverview()
      // 投稿者名・アバターはメッセージ本体に含まれるため、プロフィール変更時は一覧も取り直す
      if (b.type === 'profile') {
        void loadMessages()
        void live.current.loadThread()
      }
    })
  }, [wsId, loadOverview, loadMessages])

  useEffect(() => {
    if (!channelId || !canRead) return
    return subscribe(`/topic/channels/${channelId}`, (ev) => {
      if (ev.type === 'channelDeleted') {
        onChannelDeleted()
        void loadOverview()
        return
      }
      void loadMessages()
      const { threadId: t, loadThread: lt } = live.current
      if (t && (ev.parentId === t || ev.messageId === t)) void lt()
    })
  }, [channelId, canRead, loadMessages, loadOverview, onChannelDeleted])

  // 切断中に取りこぼした更新を再接続時にまとめて回収する
  useEffect(
    () =>
      onReconnect(() => {
        void loadOverview()
        void loadMessages()
        void live.current.loadThread()
      }),
    [loadOverview, loadMessages],
  )
}
