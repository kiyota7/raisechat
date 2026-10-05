import type { FormEvent } from 'react'
import type { ChannelInfo, DmInfo, Overview, User } from '../types'
import type { ModalType } from './types'

interface Props {
  overview: Overview
  channel: ChannelInfo | undefined
  dm: DmInfo | undefined
  dmUser: User | undefined
  isOwner: boolean
  totalUnread: number
  searchText: string
  onSearchText: (v: string) => void
  onSearch: (e: FormEvent) => void
  onOpenMenu: () => void
  onOpenModal: (m: ModalType) => void
  onDeleteChannel: () => void | Promise<void>
}

export function TopBar(p: Props) {
  const { overview, channel, dm, dmUser, isOwner } = p
  return (
    <header className="topbar">
      <button className="icon-btn menu-btn" aria-label="メニューを開く" onClick={p.onOpenMenu}>
        ☰
        {p.totalUnread > 0 && <span className="menu-dot" />}
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
      <form className="search" onSubmit={p.onSearch} role="search">
        <input
          value={p.searchText}
          onChange={(e) => p.onSearchText(e.target.value)}
          placeholder="メッセージを検索"
          aria-label="メッセージを検索"
        />
      </form>
      <div className="top-actions">
        {channel && channel.joined === 1 && <button onClick={() => p.onOpenModal('channelMembers')}>メンバー</button>}
        <button onClick={() => p.onOpenModal('members')}>ワークスペース</button>
        {isOwner && channel && channel.name !== 'general' && (
          <button className="danger" onClick={() => void p.onDeleteChannel()}>
            チャンネル削除
          </button>
        )}
      </div>
    </header>
  )
}
