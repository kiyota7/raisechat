interface Props {
  name: string
  url: string | null
  size?: number
  /** 指定したときだけ、オンライン状態の点を出せるようにする(undefinedなら何も付けない) */
  online?: boolean
}

export function Avatar({ name, url, size = 36, online }: Props) {
  const style = { width: size, height: size, fontSize: size * 0.45 }
  const face = url ? (
    <img className="avatar" src={url} alt={name} style={style} />
  ) : (
    <span className="avatar avatar-fallback" style={style} aria-label={name}>
      {name.slice(0, 1).toUpperCase()}
    </span>
  )
  if (online === undefined) return face
  return (
    <span className="avatar-wrap">
      {face}
      {online && <span className="presence-dot" role="img" aria-label="オンライン" />}
    </span>
  )
}
