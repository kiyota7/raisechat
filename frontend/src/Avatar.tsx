export function Avatar({ name, url, size = 36 }: { name: string; url: string | null; size?: number }) {
  const style = { width: size, height: size, fontSize: size * 0.45 }
  if (url) return <img className="avatar" src={url} alt={name} style={style} />
  return (
    <span className="avatar avatar-fallback" style={style} aria-label={name}>
      {name.slice(0, 1).toUpperCase()}
    </span>
  )
}
