import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Chat } from './Chat'
import type { Message, Overview, User } from './types'

/* ---- サーバーの代わり(api)とリアルタイム接続の代わり(realtime) ---- */

type Thread = { parent: Message; replies: Message[] }

const fake = vi.hoisted(() => {
  const handlers = new Map<string, Set<(body: unknown) => void>>()
  return {
    handlers,
    emitRaw: (dest: string, body: unknown) => handlers.get(dest)?.forEach((cb) => cb(body)),
    // テストごとに差し替えるデータ。api.get はここから返す
    data: {
      workspaces: [] as unknown[],
      overviews: {} as Record<number, unknown>,
      messages: {} as Record<number, unknown[]>,
      threads: {} as Record<number, unknown>,
    },
    getCalls: [] as string[],
  }
})

vi.mock('./api', () => ({
  api: {
    get: vi.fn(async (path: string) => {
      fake.getCalls.push(path)
      const ws = /^\/workspaces\/(\d+)$/.exec(path)
      const msgs = /^\/channels\/(\d+)\/messages$/.exec(path)
      const thread = /^\/messages\/(\d+)\/thread$/.exec(path)
      let body: unknown
      if (path === '/workspaces') body = fake.data.workspaces
      else if (ws) body = fake.data.overviews[Number(ws[1])]
      else if (msgs) body = fake.data.messages[Number(msgs[1])]
      else if (thread) body = fake.data.threads[Number(thread[1])]
      else throw new Error(`想定外のGET: ${path}`)
      return structuredClone(body)
    }),
    post: vi.fn(async () => undefined),
    put: vi.fn(),
    del: vi.fn(),
  },
  uploadAvatar: vi.fn(),
}))

vi.mock('./realtime', () => ({
  startRealtime: vi.fn(),
  stopRealtime: vi.fn(),
  onReconnect: () => () => {},
  subscribe: (dest: string, cb: (body: unknown) => void) => {
    const set = fake.handlers.get(dest) ?? new Set()
    set.add(cb)
    fake.handlers.set(dest, set)
    return () => set.delete(cb)
  },
}))

/* ---- テストデータ ---- */

const me: User = { id: 1, username: 'me_user', displayName: 'Me', status: '', avatarUrl: null }
const bob: User = { id: 2, username: 'bob', displayName: 'Bob', status: '', avatarUrl: null }

const channel = (id: number, name: string) => ({ id, name, isPrivate: 0, joined: 1, unread: 0, mentions: 0 })

function msg(id: number, channelId: number, author: User, content: string, extra: Partial<Message> = {}): Message {
  return {
    id,
    channelId,
    parentId: null,
    content,
    attachmentUrl: null,
    attachmentType: null,
    createdAt: new Date().toISOString(),
    editedAt: null,
    deleted: false,
    userId: author.id,
    username: author.username,
    displayName: author.displayName,
    avatarUrl: null,
    replyCount: 0,
    reactions: [],
    ...extra,
  }
}

const overview1 = (): Overview => ({
  workspace: { id: 1, name: 'W1', ownerId: 1 },
  members: [me, bob],
  channels: [channel(10, 'general'), channel(11, 'dev')],
  dms: [],
})

function resetData() {
  fake.getCalls.length = 0
  fake.handlers.clear()
  fake.data.workspaces = [
    { id: 1, name: 'W1', ownerId: 1 },
    { id: 2, name: 'W2', ownerId: 1 },
  ]
  fake.data.overviews = {
    1: overview1(),
    2: {
      workspace: { id: 2, name: 'W2', ownerId: 1 },
      members: [me],
          channels: [channel(20, 'general')],
      dms: [],
    } satisfies Overview,
  }
  const devMsg = msg(2, 11, bob, 'msg-in-dev', { replyCount: 1 })
  fake.data.messages = {
    10: [msg(1, 10, bob, 'msg-in-general')],
    11: [devMsg],
    20: [msg(3, 20, me, 'msg-in-w2')],
  }
  fake.data.threads = {
    2: { parent: devMsg, replies: [msg(4, 11, bob, 'reply-in-thread', { parentId: 2 })] } satisfies Thread,
  }
}

const emit = (dest: string, body: unknown) => act(() => fake.emitRaw(dest, body))
const messageCalls = (channelId: number) => fake.getCalls.filter((p) => p === `/channels/${channelId}/messages`).length
const header = () => within(screen.getByRole('banner'))
const side = (name: string) => screen.getByRole('button', { name })

function renderChat() {
  return render(<Chat user={me} onUserChange={() => {}} onLogout={() => {}} />)
}

beforeEach(() => {
  resetData()
  localStorage.setItem('raisechat_ws', '1')
})

/* ---- テスト ---- */

describe('Chat: 表示とチャンネル・ワークスペースの切り替え', () => {
  it('選択がなければ #general を表示し、そのメッセージを読み込む', async () => {
    renderChat()
    expect(await screen.findByText('msg-in-general')).toBeTruthy()
    expect(header().getByText('# general')).toBeTruthy()
  })

  it('チャンネルを切り替えるとメッセージが入れ替わり、開いていたスレッドが閉じる', async () => {
    const user = userEvent.setup()
    renderChat()
    await screen.findByText('msg-in-general')

    await user.click(side('# dev'))
    expect(await screen.findByText('msg-in-dev')).toBeTruthy()
    expect(screen.queryByText('msg-in-general')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'スレッドで返信' }))
    expect(await screen.findByText('reply-in-thread')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'スレッド' })).toBeTruthy()

    await user.click(side('# general'))
    expect(await screen.findByText('msg-in-general')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'スレッド' })).toBeNull()
    expect(screen.queryByText('reply-in-thread')).toBeNull()
  })

  it('ワークスペースを切り替えると、そのワークスペースの #general に切り替わり、戻ると元の #general になる', async () => {
    const user = userEvent.setup()
    renderChat()
    await screen.findByText('msg-in-general')
    await user.click(side('# dev'))
    await screen.findByText('msg-in-dev')

    await user.selectOptions(screen.getByLabelText('ワークスペース'), 'W2')
    expect(await screen.findByText('msg-in-w2')).toBeTruthy()
    expect(header().getByText('# general')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '# dev' })).toBeNull()

    await user.selectOptions(screen.getByLabelText('ワークスペース'), 'W1')
    expect(await screen.findByText('msg-in-general')).toBeTruthy()
    expect(header().getByText('# general')).toBeTruthy()
  })

  it('見ているチャンネルが削除されたら、#general に戻る', async () => {
    const user = userEvent.setup()
    renderChat()
    await screen.findByText('msg-in-general')
    await user.click(side('# dev'))
    await screen.findByText('msg-in-dev')

    const ov = overview1()
    ov.channels = [channel(10, 'general')]
    fake.data.overviews[1] = ov
    await emit('/topic/channels/11', { type: 'channelDeleted', channelId: 11 })

    expect(await screen.findByText('msg-in-general')).toBeTruthy()
    expect(header().getByText('# general')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '# dev' })).toBeNull()
  })
})

describe('Chat: プロフィール変更の反映', () => {
  it('プロフィール変更の合図で、メッセージ一覧を取り直して新しい名前を表示する', async () => {
    renderChat()
    await screen.findByText('msg-in-general')
    expect(screen.queryByText('Bobby')).toBeNull()

    fake.data.messages[10] = [msg(1, 10, { ...bob, displayName: 'Bobby' }, 'msg-in-general')]
    await emit('/user/queue/overview', { type: 'profile', workspaceId: 1 })

    expect(await screen.findByText('Bobby')).toBeTruthy()
  })

  it('通常のサイドバー更新の合図では、メッセージ一覧を取り直さない', async () => {
    renderChat()
    await screen.findByText('msg-in-general')
    const before = messageCalls(10)

    await emit('/user/queue/overview', { type: 'overview', workspaceId: 1 })
    await waitFor(() => expect(fake.getCalls.filter((p) => p === '/workspaces/1').length).toBeGreaterThan(1))
    expect(messageCalls(10)).toBe(before)

    await emit('/user/queue/overview', { type: 'profile', workspaceId: 1 })
    await waitFor(() => expect(messageCalls(10)).toBe(before + 1))
  })

  it('他のワークスペースの合図は無視する', async () => {
    renderChat()
    await screen.findByText('msg-in-general')
    const overviewBefore = fake.getCalls.filter((p) => p === '/workspaces/1').length

    await emit('/user/queue/overview', { type: 'profile', workspaceId: 2 })
    expect(fake.getCalls.filter((p) => p === '/workspaces/1').length).toBe(overviewBefore)
  })
})
