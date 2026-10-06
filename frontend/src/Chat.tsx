import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api, type ThreadData } from './api'
import type { Draft } from './Composer'
import { ChannelMembersModal, MembersModal, ProfileModal, TextModal } from './chat/modals'
import { MessagePane } from './chat/MessagePane'
import { SearchResults } from './chat/SearchResults'
import { Sidebar } from './chat/Sidebar'
import { ThreadPane } from './chat/ThreadPane'
import { TopBar } from './chat/TopBar'
import type { ModalType } from './chat/types'
import { useChatRealtime } from './chat/useChatRealtime'
import type { Message, Overview, SearchResult, User, Workspace } from './types'

interface Props {
  user: User
  onUserChange: (u: User) => void
  onLogout: () => void
}

export function Chat({ user, onUserChange, onLogout }: Props) {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [loaded, setLoaded] = useState(false)
  const [wsId, setWsId] = useState<number | null>(() => Number(localStorage.getItem('raisechat_ws')) || null)
  const [overview, setOverview] = useState<Overview | null>(null)
  // 選択中のチャンネル。null のときは既定のチャンネル(下の channelId)を表示する
  const [selectedChannelId, setChannelId] = useState<number | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [forbidden, setForbidden] = useState(false)
  const [threadId, setThreadId] = useState<number | null>(null)
  const [thread, setThread] = useState<ThreadData | null>(null)
  const [modal, setModal] = useState<ModalType>(null)
  const [searchText, setSearchText] = useState('')
  const [results, setResults] = useState<SearchResult[] | null>(null)
  const [toast, setToast] = useState('')
  const [navOpen, setNavOpen] = useState(false)
  const lastSeenMsg = useRef(0)
  const lastRead = useRef(0)
  const prevMentions = useRef(0)
  const listEnd = useRef<HTMLDivElement>(null)
  const stickBottom = useRef(true)

  const showToast = useCallback((m: string) => {
    setToast(m)
    setTimeout(() => setToast(''), 4000)
  }, [])

  const loadWorkspaces = useCallback(async () => {
    const list = await api.get<Workspace[]>('/workspaces')
    setWorkspaces(list)
    setLoaded(true)
    setWsId((cur) => (cur && list.some((w) => w.id === cur) ? cur : (list[0]?.id ?? null)))
  }, [])

  useEffect(() => {
    // 非同期のデータ取得。setStateは await の後で呼ばれる
    // oxlint-disable-next-line react/set-state-in-effect
    void loadWorkspaces()
  }, [loadWorkspaces])

  // ワークスペースを切り替えたら、前の画面の状態を捨てる(描画中の状態調整。effectで行うと一度古い状態を描画してしまう)
  const [shownWsId, setShownWsId] = useState(wsId)
  if (wsId !== shownWsId) {
    setShownWsId(wsId)
    setChannelId(null)
    setOverview(null)
    setThreadId(null)
    setResults(null)
  }
  useEffect(() => {
    if (wsId) localStorage.setItem('raisechat_ws', String(wsId))
    prevMentions.current = 0
  }, [wsId])

  const loadOverview = useCallback(async () => {
    if (!wsId) return
    try {
      const ov = await api.get<Overview>(`/workspaces/${wsId}`)
      setOverview(ov)
      const mentions = [...ov.channels, ...ov.dms].reduce((s, c) => s + c.mentions, 0)
      if (mentions > prevMentions.current) showToast('メンションされました')
      prevMentions.current = mentions
    } catch (e) {
      // キックされた場合などはワークスペース一覧を取り直す
      if ((e as { status?: number }).status === 403) void loadWorkspaces()
    }
  }, [wsId, loadWorkspaces, showToast])
  useEffect(() => {
    // 非同期のデータ取得。setStateは await の後で呼ばれる
    // oxlint-disable-next-line react/set-state-in-effect
    if (wsId) void loadOverview()
  }, [wsId, loadOverview])

  // 選択がないときは #general (なければ最初の参加チャンネル)を表示する
  const defaultChannel = overview
    ? (overview.channels.find((c) => c.joined && c.name === 'general') ?? overview.channels.find((c) => c.joined))
    : undefined
  const channelId = selectedChannelId ?? defaultChannel?.id ?? null

  const channel = overview?.channels.find((c) => c.id === channelId)
  const dm = overview?.dms.find((d) => d.id === channelId)
  const dmUser = dm ? overview?.members.find((m) => m.id === dm.userId) : undefined
  const isOwner = overview?.workspace.ownerId === user.id
  const canRead = !!channelId && (!!dm || channel?.joined === 1)

  // チャンネルを切り替えたら、前のチャンネルのメッセージとスレッドを捨てる(描画中の状態調整)
  const [shownChannelId, setShownChannelId] = useState(channelId)
  if (channelId !== shownChannelId) {
    setShownChannelId(channelId)
    setMessages([])
    setForbidden(false)
    setThreadId(null)
  }
  useEffect(() => {
    lastRead.current = 0
    lastSeenMsg.current = 0
    stickBottom.current = true
  }, [channelId])

  const loadMessages = useCallback(async () => {
    if (!channelId || !canRead) return
    try {
      const list = await api.get<Message[]>(`/channels/${channelId}/messages`)
      setMessages(list)
      const last = list.length ? list[list.length - 1].id : 0
      if (last > lastSeenMsg.current) {
        lastSeenMsg.current = last
      }
      if (last > lastRead.current) {
        lastRead.current = last
        void api.post(`/channels/${channelId}/read`)
      }
    } catch (e) {
      if ((e as { status?: number }).status === 403) setForbidden(true)
    }
  }, [channelId, canRead])
  useEffect(() => {
    // 非同期のデータ取得。setStateは await の後で呼ばれる
    // oxlint-disable-next-line react/set-state-in-effect
    if (canRead) void loadMessages()
  }, [canRead, loadMessages])

  const loadThread = useCallback(async () => {
    if (!threadId) return
    try {
      setThread(await api.get<ThreadData>(`/messages/${threadId}/thread`))
    } catch {
      setThread(null)
    }
  }, [threadId])
  // 開くスレッドが変わったら、前のスレッドの内容を捨てる(描画中の状態調整)
  const [shownThreadId, setShownThreadId] = useState(threadId)
  if (threadId !== shownThreadId) {
    setShownThreadId(threadId)
    setThread(null)
  }
  useEffect(() => {
    // 非同期のデータ取得。setStateは await の後で呼ばれる
    // oxlint-disable-next-line react/set-state-in-effect
    if (threadId) void loadThread()
  }, [threadId, loadThread])

  const clearSelectedChannel = useCallback(() => setChannelId(null), [])
  useChatRealtime({ wsId, channelId, canRead, threadId, loadOverview, loadMessages, loadThread, onChannelDeleted: clearSelectedChannel })

  useEffect(() => {
    if (stickBottom.current) listEnd.current?.scrollIntoView({ block: 'end' })
  }, [messages])

  const totalUnread = overview ? [...overview.channels, ...overview.dms].reduce((s, c) => s + c.unread, 0) : 0
  useEffect(() => {
    document.title = totalUnread > 0 ? `(${totalUnread}) RaiseChat` : 'RaiseChat'
  }, [totalUnread])

  const refresh = () => {
    void loadMessages()
    void loadThread()
    void loadOverview()
  }

  const post = async (d: Draft, parentId?: number) => {
    await api.post(`/channels/${channelId}/messages`, { ...d, parentId })
    stickBottom.current = true
    refresh()
  }

  const selectChannel = (id: number) => {
    setResults(null)
    setChannelId(id)
    setNavOpen(false)
  }

  const openDm = async (userId: number) => {
    try {
      const r = await api.post<{ id: number }>(`/workspaces/${wsId}/dms`, { userId })
      setModal(null)
      selectChannel(r.id)
      void loadOverview()
    } catch (e) {
      showToast((e as Error).message)
    }
  }

  const doSearch = async (e: FormEvent) => {
    e.preventDefault()
    if (!searchText.trim() || !wsId) return
    setResults(await api.get<SearchResult[]>(`/workspaces/${wsId}/search?q=${encodeURIComponent(searchText.trim())}`))
  }

  const join = async () => {
    await api.post(`/channels/${channelId}/join`)
    void loadOverview()
  }

  const deleteChannel = async () => {
    if (!channel || !confirm(`#${channel.name} を削除しますか? メッセージもすべて削除されます。`)) return
    try {
      await api.del(`/channels/${channel.id}`)
      setChannelId(null)
      void loadOverview()
    } catch (e) {
      showToast((e as Error).message)
    }
  }

  if (!loaded) return <div className="center-screen">読み込み中...</div>

  const members = overview?.members ?? []

  return (
    <div className="app">
      {navOpen && <div className="nav-backdrop" onClick={() => setNavOpen(false)} />}
      <Sidebar
        user={user}
        workspaces={workspaces}
        wsId={wsId}
        overview={overview}
        channelId={channelId}
        searching={!!results}
        members={members}
        open={navOpen}
        onSelectWorkspace={setWsId}
        onSelectChannel={selectChannel}
        onOpenModal={setModal}
        onLogout={onLogout}
      />

      <main className="main">
        {!wsId || !overview ? (
          <div className="empty">
            {wsId ? (
              '読み込み中...'
            ) : (
              <>
                <h2>ワークスペースを作成しましょう</h2>
                <p className="muted">ワークスペースを作成するとオーナーとしてメンバーの招待やチャンネル管理ができます。</p>
                <button className="primary" onClick={() => setModal('newWs')}>
                  ワークスペースを作成
                </button>
              </>
            )}
          </div>
        ) : (
          <>
            <TopBar
              overview={overview}
              channel={channel}
              dm={dm}
              dmUser={dmUser}
              isOwner={isOwner}
              totalUnread={totalUnread}
              searchText={searchText}
              onSearchText={setSearchText}
              onSearch={doSearch}
              onOpenMenu={() => setNavOpen(true)}
              onOpenModal={setModal}
              onDeleteChannel={deleteChannel}
            />

            <div className="content">
              <section className="pane">
                {results ? (
                  <SearchResults
                    results={results}
                    searchText={searchText}
                    onClose={() => setResults(null)}
                    onPick={(r) => {
                      setResults(null)
                      selectChannel(r.channelId)
                      setThreadId(r.parentId ?? null)
                    }}
                  />
                ) : !channelId ? (
                  <div className="empty muted">チャンネルを選択してください</div>
                ) : !canRead || forbidden ? (
                  <div className="empty">
                    <h2># {channel?.name}</h2>
                    <p className="muted">このチャンネルに参加するとメッセージを読み書きできます。</p>
                    <button className="primary" onClick={() => void join()}>
                      チャンネルに参加する
                    </button>
                  </div>
                ) : (
                  <MessagePane
                    messages={messages}
                    user={user}
                    members={members}
                    listEnd={listEnd}
                    onNearBottomChange={(near) => {
                      stickBottom.current = near
                    }}
                    composerKey={channelId}
                    placeholder={dm ? `${dmUser?.displayName ?? ''}へのメッセージ` : `#${channel?.name ?? ''} へのメッセージ`}
                    onChanged={refresh}
                    onOpenThread={setThreadId}
                    onError={showToast}
                    onSend={(d) => post(d)}
                  />
                )}
              </section>

              {threadId && !results && (
                <ThreadPane
                  thread={thread}
                  threadId={threadId}
                  user={user}
                  members={members}
                  onClose={() => setThreadId(null)}
                  onChanged={refresh}
                  onError={showToast}
                  onSend={post}
                />
              )}
            </div>
          </>
        )}
      </main>

      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}

      {modal === 'newWs' && (
        <TextModal
          title="ワークスペースを作成"
          label="ワークスペース名"
          submitLabel="作成"
          onClose={() => setModal(null)}
          onSubmit={async (name) => {
            const w = await api.post<Workspace>('/workspaces', { name })
            await loadWorkspaces()
            setWsId(w.id)
          }}
        />
      )}
      {modal === 'newCh' && (
        <TextModal
          title="チャンネルを作成"
          label="チャンネル名"
          submitLabel="作成"
          checkbox="プライベートチャンネルにする(招待制)"
          onClose={() => setModal(null)}
          onSubmit={async (name, isPrivate) => {
            const c = await api.post<{ id: number }>(`/workspaces/${wsId}/channels`, { name, isPrivate })
            await loadOverview()
            selectChannel(c.id)
          }}
        />
      )}
      {modal === 'inviteWs' && (
        <TextModal
          title="ワークスペースに招待"
          label="招待するユーザーのユーザーID"
          submitLabel="招待"
          onClose={() => setModal('members')}
          onSubmit={async (username) => {
            await api.post(`/workspaces/${wsId}/invite`, { username })
            await loadOverview()
            showToast(`${username} を招待しました`)
          }}
        />
      )}
      {modal === 'inviteCh' && channel && (
        <TextModal
          title={`#${channel.name} に招待`}
          label="ワークスペースメンバーのユーザーID"
          submitLabel="招待"
          onClose={() => setModal('channelMembers')}
          onSubmit={async (username) => {
            await api.post(`/channels/${channel.id}/members`, { username })
            showToast(`${username} を招待しました`)
          }}
        />
      )}
      {modal === 'profile' && (
        <ProfileModal user={user} onClose={() => setModal(null)} onSaved={onUserChange} />
      )}
      {modal === 'members' && overview && (
        <MembersModal
          overview={overview}
          wsId={wsId}
          user={user}
          isOwner={isOwner}
          onOpenDm={openDm}
          onChanged={loadOverview}
          onError={showToast}
          onInvite={() => setModal('inviteWs')}
          onClose={() => setModal(null)}
        />
      )}
      {modal === 'channelMembers' && channel && (
        <ChannelMembersModal
          channelId={channel.id}
          title={`${channel.isPrivate ? '🔒' : '#'} ${channel.name} のメンバー`}
          onInvite={() => setModal('inviteCh')}
          onClose={() => setModal(null)}
        />
      )}
    </div>
  )
}
