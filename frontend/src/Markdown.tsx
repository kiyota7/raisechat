import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { User } from './types'

const MENTION_RE = /(^|[^A-Za-z0-9_])@([A-Za-z0-9_]{3,20})/g

function safeUrl(url: string) {
  return /^(https?:|mailto:|mention:)/i.test(url) ? url : ''
}

/** マークダウン本文を描画し、ワークスペースメンバーへの@メンションを強調する */
export function Markdown({ text, members, selfId }: { text: string; members: User[]; selfId: number }) {
  const byName = new Map(members.map((m) => [m.username, m]))
  const source = text.replace(MENTION_RE, (all, pre: string, name: string) =>
    byName.has(name) ? `${pre}[@${name}](mention:${name})` : all,
  )
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        urlTransform={safeUrl}
        components={{
          a: ({ href, children }) => {
            if (href?.startsWith('mention:')) {
              const u = byName.get(href.slice(8))
              return (
                <span className={`mention${u?.id === selfId ? ' mention-self' : ''}`} title={u?.displayName}>
                  {children}
                </span>
              )
            }
            return (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            )
          },
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  )
}
