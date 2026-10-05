import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api, type ThreadData } from './api'
import { onReconnect, send, startRealtime, stopRealtime, subscribe } from './realtime'
import { Avatar } from './Avatar'
import { Composer, type Draft } from './Composer'
import { formatTime } from './format'
import { MessageItem } from './MessageItem'
import { ChannelMembersModal, MembersModal, ProfileModal, TextModal } from './chat/modals'
import type { ModalType } from './chat/types'
import type { Message, Overview, SearchResult, User, Workspace } from './types'

/** 最後の入力中通知からこの時間だけ「入力中」を表示する */
const TYPING_SHOW_MS = 3500

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
  const [onlineIds, setOnlineIds] = useState<Set<number>>(new Set())
  const [typingIds, setTypingIds] = useState<number[]>([])
  const typingTimers = useRef(new Map<number, ReturnType<typeof setTimeout>>())
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
      setOnlineIds(new Set(ov.onlineUserIds))
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
        // 投稿された時点で、その人の「入力中」は消す
        const author = list[list.length - 1]?.userId
        if (author !== undefined) {
          clearTimeout(typingTimers.current.get(author))
          typingTimers.current.delete(author)
          setTypingIds((cur) => cur.filter((x) => x !== author))
        }
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

  // リアルタイム更新(STOMP)。イベントは再取得の合図で、データは従来のRESTで取り直す
  useEffect(() => {
    startRealtime()
    return stopRealtime
  }, [])

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

  // 入力中の通知。最後の通知から一定時間で消す(自分の分は表示しない)
  useEffect(() => {
    if (!channelId || !canRead) return
    const timers = typingTimers.current
    const stop = subscribe(`/topic/channels/${channelId}/typing`, (ev) => {
      const uid = ev.userId as number
      if (uid === user.id) return
      clearTimeout(timers.get(uid))
      timers.set(
        uid,
        setTimeout(() => {
          timers.delete(uid)
          setTypingIds((cur) => cur.filter((x) => x !== uid))
        }, TYPING_SHOW_MS),
      )
      setTypingIds((cur) => (cur.includes(uid) ? cur : [...cur, uid]))
    })
    return () => {
      stop()
      timers.forEach(clearTimeout)
      timers.clear()
      setTypingIds([])
    }
  }, [channelId, canRead, user.id])

  useEffect(() => {
    if (!channelId || !canRead) return
    return subscribe(`/topic/channels/${channelId}`, (ev) => {
      if (ev.type === 'channelDeleted') {
        setChannelId(null)
        void loadOverview()
        return
      }
      void loadMessages()
      const { threadId: t, loadThread: lt } = live.current
      if (t && (ev.parentId === t || ev.messageId === t)) void lt()
    })
  }, [channelId, canRead, loadMessages, loadOverview])

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
  const typingNames = typingIds.map((id) => members.find((m) => m.id === id)?.displayName).filter((n): n is string => !!n)
  const typingText =
    typingNames.length === 0 ? '' : typingNames.length <= 2 ? `${typingNames.join('、')}が入力中...` : `${typingNames.length}人が入力中...`
  const notifyTyping = () => {
    if (channelId) send('/app/typing', { channelId })
  }

  return (
    <div className="app">
      {navOpen && <div className="nav-backdrop" onClick={() => setNavOpen(false)} />}
      <aside className={`sidebar${navOpen ? ' open' : ''}`}>
        <div className="side-head">
          <select
            aria-label="ワークスペース"
            value={wsId ?? ''}
            onChange={(e) => (e.target.value === 'new' ? setModal('newWs') : setWsId(Number(e.target.value)))}
          >
            {workspaces.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
            <option value="new">＋ 新しいワークスペース</option>
          </select>
        </div>

        {overview && (
          <div className="side-scroll">
            <div className="side-section">
              <span>チャンネル</span>
              <button className="icon-btn" aria-label="チャンネルを作成" title="チャンネルを作成" onClick={() => setModal('newCh')}>
                ＋
              </button>
            </div>
            {overview.channels.map((c) => (
              <button
                key={c.id}
                className={`side-item${c.id === channelId && !results ? ' active' : ''}${c.unread > 0 ? ' unread' : ''}${c.joined ? '' : ' dim'}`}
                onClick={() => selectChannel(c.id)}
              >
                <span>
                  {c.isPrivate ? '🔒' : '#'} {c.name}
                </span>
                {c.mentions > 0 ? (
                  <span className="badge badge-mention">@{c.mentions}</span>
                ) : (
                  c.unread > 0 && <span className="badge">{c.unread}</span>
                )}
              </button>
            ))}

            <div className="side-section">
              <span>ダイレクトメッセージ</span>
              <button className="icon-btn" aria-label="メンバー一覧" title="メンバー一覧" onClick={() => setModal('members')}>
                ＋
              </button>
            </div>
            {overview.dms.map((d) => {
              const u = members.find((m) => m.id === d.userId)
              return (
                <button
                  key={d.id}
                  className={`side-item${d.id === channelId && !results ? ' active' : ''}${d.unread > 0 ? ' unread' : ''}`}
                  onClick={() => selectChannel(d.id)}
                >
                  <span>
                    <Avatar name={u?.displayName ?? '?'} url={u?.avatarUrl ?? null} size={18} online={onlineIds.has(d.userId)} /> {u?.displayName ?? '(退会済み)'}
                  </span>
                  {d.mentions > 0 ? (
                    <span className="badge badge-mention">@{d.mentions}</span>
                  ) : (
                    d.unread > 0 && <span className="badge">{d.unread}</span>
                  )}
                </button>
              )
            })}
          </div>
        )}

        <div className="side-foot">
          <button className="me" onClick={() => setModal('profile')} title="プロフィール設定">
            <Avatar name={user.displayName} url={user.avatarUrl} size={32} />
            <span className="me-text">
              <strong>{user.displayName}</strong>
              <span className="muted small">{user.status || '@' + user.username}</span>
            </span>
          </button>
          <button onClick={onLogout}>ログアウト</button>
        </div>
      </aside>

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
            <header className="topbar">
              <button className="icon-btn menu-btn" aria-label="メニューを開く" onClick={() => setNavOpen(true)}>
                ☰
                {totalUnread > 0 && <span className="menu-dot" />}
              </button>
              <div className="title">
                {dm ? (
                  <strong>{dmUser?.displayName ?? 'DM'}</strong>
                ) : channel ? (
                  <strong>
                    {channel.isPrivate ? '🔒' : '#'} {channel.name}
                  </strong>
                ) : (
                  <strong>{overview.workspace.name}</strong>
                )}
                {isOwner && <span className="owner-tag">オーナー</span>}
              </div>
              <form className="search" onSubmit={doSearch} role="search">
                <input
                  value={searchText}
                  onChange={(e) => setSearchText(e.target.value)}
                  placeholder="メッセージを検索"
                  aria-label="メッセージを検索"
                />
              </form>
              <div className="top-actions">
                {channel && channel.joined === 1 && (
                  <button onClick={() => setModal('channelMembers')}>メンバー</button>
                )}
                <button onClick={() => setModal('members')}>ワークスペース</button>
                {isOwner && channel && channel.name !== 'general' && (
                  <button className="danger" onClick={() => void deleteChannel()}>
                    チャンネル削除
                  </button>
                )}
              </div>
            </header>

            <div className="content">
              <section className="pane">
                {results ? (
                  <div className="messages">
                    <div className="row between">
                      <h3>「{searchText}」の検索結果 ({results.length}件)</h3>
                      <button onClick={() => setResults(null)}>閉じる</button>
                    </div>
                    {results.length === 0 && <p className="muted">該当するメッセージはありません</p>}
                    {results.map((r) => (
                      <button
                        key={r.id}
                        className="result"
                        onClick={() => {
                          setResults(null)
                          selectChannel(r.channelId)
                          setThreadId(r.parentId ?? null)
                        }}
                      >
                        <div className="muted small">
                          {r.isDm ? 'DM' : `# ${r.channelName}`} ・ {r.displayName} ・ {formatTime(r.createdAt)}
                        </div>
                        <div>{r.content}</div>
                      </button>
                    ))}
                  </div>
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
                  <>
                    <div
                      className="messages"
                      onScroll={(e) => {
                        const el = e.currentTarget
                        stickBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
                      }}
                    >
                      {messages.length === 0 && <p className="muted">まだメッセージはありません。最初の投稿をしてみましょう。</p>}
                      {messages.map((m) => (
                        <MessageItem
                          key={m.id}
                          msg={m}
                          me={user}
                          members={members}
                          onlineIds={onlineIds}
                          onChanged={refresh}
                          onOpenThread={setThreadId}
                          onError={showToast}
                        />
                      ))}
                      <div ref={listEnd} />
                    </div>
                    <div className="typing" aria-live="polite">
                      {typingText}
                    </div>
                    <Composer
                      key={channelId}
                      placeholder={dm ? `${dmUser?.displayName ?? ''}へのメッセージ` : `#${channel?.name ?? ''} へのメッセージ`}
                      members={members}
                      onSend={(d) => post(d)}
                      onTyping={notifyTyping}
                    />
                  </>
                )}
              </section>

              {threadId && !results && (
                <aside className="thread">
                  <div className="row between">
                    <h3>スレッド</h3>
                    <button className="icon-btn" aria-label="スレッドを閉じる" onClick={() => setThreadId(null)}>
                      ✕
                    </button>
                  </div>
                  {thread ? (
                    <>
                      <div className="messages">
                        <MessageItem msg={thread.parent} me={user} members={members} onlineIds={onlineIds} inThread onChanged={refresh} onError={showToast} />
                        <div className="divider muted small">{thread.replies.length}件の返信</div>
                        {thread.replies.map((m) => (
                          <MessageItem key={m.id} msg={m} me={user} members={members} onlineIds={onlineIds} inThread onChanged={refresh} onError={showToast} />
                        ))}
                      </div>
                      {!thread.parent.deleted && (
                        <Composer
                          key={`t${threadId}`}
                          placeholder="返信する"
                          members={members}
                          onSend={(d) => post(d, thread.parent.id)}
                          onTyping={notifyTyping}
                        />
                      )}
                    </>
                  ) : (
                    <p className="muted">読み込み中...</p>
                  )}
                </aside>
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
          onlineIds={onlineIds}
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
