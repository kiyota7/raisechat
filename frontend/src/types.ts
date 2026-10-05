export interface User {
  id: number
  username: string
  displayName: string
  status: string
  avatarUrl: string | null
}

export interface Workspace {
  id: number
  name: string
  ownerId: number
}

export interface ChannelInfo {
  id: number
  name: string
  isPrivate: number
  joined: number
  unread: number
  mentions: number
}

export interface DmInfo {
  id: number
  userId: number
  unread: number
  mentions: number
}

export interface Overview {
  workspace: Workspace
  members: User[]
  onlineUserIds: number[]
  channels: ChannelInfo[]
  dms: DmInfo[]
}

export interface Reaction {
  emoji: string
  userIds: number[]
}

export interface Message {
  id: number
  channelId: number
  parentId: number | null
  content: string
  attachmentUrl: string | null
  attachmentType: 'image' | 'video' | null
  createdAt: string
  editedAt: string | null
  deleted: boolean
  userId: number
  username: string
  displayName: string
  avatarUrl: string | null
  replyCount: number
  reactions: Reaction[]
}

export interface SearchResult {
  id: number
  channelId: number
  parentId: number | null
  channelName: string
  isDm: number
  content: string
  createdAt: string
  displayName: string
  avatarUrl: string | null
}
