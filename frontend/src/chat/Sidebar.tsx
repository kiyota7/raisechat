import { Avatar } from '../Avatar'
import type { Overview, User, Workspace } from '../types'
import type { ModalType } from './types'

interface Props {
  user: User
  workspaces: Workspace[]
  wsId: number | null
  overview: Overview | null
  channelId: number | null
  /** 検索結果を表示中は、選択中のチャンネルを強調しない */
  searching: boolean
  members: User[]
  open: boolean
  onSelectWorkspace: (id: number) => void
  onSelectChannel: (id: number) => void
  onOpenModal: (m: ModalType) => void
  onLogout: () => void
}

export function Sidebar(p: Props) {
  const { user, overview, channelId, searching, members } = p
  return (
    <aside className={`sidebar${p.open ? ' open' : ''}`}>
      <div className="side-head">
        <select
          aria-label="ワークスペース"
          value={p.wsId ?? ''}
          onChange={(e) => (e.target.value === 'new' ? p.onOpenModal('newWs') : p.onSelectWorkspace(Number(e.target.value)))}
        >
          {p.workspaces.map((w) => (
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
            <button className="icon-btn" aria-label="チャンネルを作成" title="チャンネルを作成" onClick={() => p.onOpenModal('newCh')}>
              ＋
            </button>
          </div>
          {overview.channels.map((c) => (
            <button
              key={c.id}
              className={`side-item${c.id === channelId && !searching ? ' active' : ''}${c.unread > 0 ? ' unread' : ''}${c.joined ? '' : ' dim'}`}
              onClick={() => p.onSelectChannel(c.id)}
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
            <button className="icon-btn" aria-label="メンバー一覧" title="メンバー一覧" onClick={() => p.onOpenModal('members')}>
              ＋
            </button>
          </div>
          {overview.dms.map((d) => {
            const u = members.find((m) => m.id === d.userId)
            return (
              <button
                key={d.id}
                className={`side-item${d.id === channelId && !searching ? ' active' : ''}${d.unread > 0 ? ' unread' : ''}`}
                onClick={() => p.onSelectChannel(d.id)}
              >
                <span>
                  <Avatar name={u?.displayName ?? '?'} url={u?.avatarUrl ?? null} size={18} /> {u?.displayName ?? '(退会済み)'}
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
        <button className="me" onClick={() => p.onOpenModal('profile')} title="プロフィール設定">
          <Avatar name={user.displayName} url={user.avatarUrl} size={32} />
          <span className="me-text">
            <strong>{user.displayName}</strong>
            <span className="muted small">{user.status || '@' + user.username}</span>
          </span>
        </button>
        <button onClick={p.onLogout}>ログアウト</button>
      </div>
    </aside>
  )
}
