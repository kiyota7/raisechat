/** 当日なら時刻のみ、それ以外は日付つきで表示する */
export function formatTime(iso: string) {
  const d = new Date(iso)
  const today = new Date()
  const time = d.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' })
  return d.toDateString() === today.toDateString() ? time : `${d.toLocaleDateString('ja-JP')} ${time}`
}
