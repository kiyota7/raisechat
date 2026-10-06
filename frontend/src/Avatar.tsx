interface Props {
  name: string
  url: string | null
  size?: number
}

export function Avatar({ name, url, size = 36 }: Props) {
  const style = { width: size, height: size, fontSize: size * 0.45 }
  return url ? (
    <img className="avatar" src={url} alt={name} style={style} />
  ) : (
    <span className="avatar avatar-fallback" style={style} aria-label={name}>
      {name.slice(0, 1).toUpperCase()}
    </span>
  )
}
