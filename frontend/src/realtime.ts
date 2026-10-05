import { Client, type StompSubscription } from '@stomp/stompjs'
import { api, getToken } from './api'

type Handler = (body: any) => void
interface Entry {
  dest: string
  cb: Handler
  sub?: StompSubscription
}

const entries = new Set<Entry>()
const reconnectCbs = new Set<() => void>()
let client: Client | null = null

function subscribeEntry(e: Entry) {
  if (!client?.connected) return
  e.sub = client.subscribe(e.dest, (msg) => e.cb(JSON.parse(msg.body)))
}

export function startRealtime() {
  if (client) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const c = new Client({
    brokerURL: `${proto}://${location.host}/ws`,
    reconnectDelay: 3000,
    beforeConnect: () => {
      c.connectHeaders = { Authorization: `Bearer ${getToken() ?? ''}` }
    },
    onConnect: () => {
      entries.forEach(subscribeEntry)
      reconnectCbs.forEach((cb) => cb())
    },
    onStompError: () => {
      // JWT失効などで拒否された場合、REST側の401処理でログイン画面に戻す
      void api.get('/me').catch(() => {})
    },
  })
  client = c
  c.activate()
}

export function stopRealtime() {
  const c = client
  client = null
  entries.forEach((e) => (e.sub = undefined))
  void c?.deactivate()
}

/** 接続前でも呼べる。再接続時は自動で張り直される。戻り値で購読解除 */
export function subscribe(dest: string, cb: Handler): () => void {
  const e: Entry = { dest, cb }
  entries.add(e)
  subscribeEntry(e)
  return () => {
    entries.delete(e)
    try {
      e.sub?.unsubscribe()
    } catch {
      /* 切断済み */
    }
  }
}

/** STOMPでサーバーへ送る(入力中の通知用)。未接続のときは何もしない */
export function send(dest: string, body: unknown) {
  if (!client?.connected) return
  client.publish({ destination: dest, body: JSON.stringify(body), headers: { 'content-type': 'application/json' } })
}

/** 接続(再接続を含む)が確立するたびに呼ばれる */
export function onReconnect(cb: () => void): () => void {
  reconnectCbs.add(cb)
  return () => {
    reconnectCbs.delete(cb)
  }
}
