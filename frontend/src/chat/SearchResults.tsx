import { formatTime } from '../format'
import type { SearchResult } from '../types'

interface Props {
  results: SearchResult[]
  searchText: string
  onClose: () => void
  onPick: (r: SearchResult) => void
}

export function SearchResults({ results, searchText, onClose, onPick }: Props) {
  return (
    <div className="messages">
      <div className="row between">
        <h3>「{searchText}」の検索結果 ({results.length}件)</h3>
        <button onClick={onClose}>閉じる</button>
      </div>
      {results.length === 0 && <p className="muted">該当するメッセージはありません</p>}
      {results.map((r) => (
        <button key={r.id} className="result" onClick={() => onPick(r)}>
          <div className="muted small">
            {r.isDm ? 'DM' : `# ${r.channelName}`} ・ {r.displayName} ・ {formatTime(r.createdAt)}
          </div>
          <div>{r.content}</div>
        </button>
      ))}
    </div>
  )
}
