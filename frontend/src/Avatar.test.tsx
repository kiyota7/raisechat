import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Avatar } from './Avatar'

describe('Avatar', () => {
  it('画像がなければ名前の頭文字を表示する', () => {
    render(<Avatar name="alice" url={null} />)
    expect(screen.getByLabelText('alice').textContent).toBe('A')
  })

  it('画像があれば画像を表示する', () => {
    render(<Avatar name="alice" url="/uploads/a.png" />)
    expect(screen.getByAltText('alice').getAttribute('src')).toBe('/uploads/a.png')
  })

  it('online が未指定ならオンライン表示の部品を付けない(従来の表示のまま)', () => {
    const { container } = render(<Avatar name="alice" url={null} />)
    expect(container.querySelector('.avatar-wrap')).toBeNull()
    expect(screen.queryByRole('img', { name: 'オンライン' })).toBeNull()
  })

  it('online=true のときだけ緑の点(オンライン)を表示する', () => {
    const { rerender } = render(<Avatar name="alice" url={null} online />)
    expect(screen.getByRole('img', { name: 'オンライン' })).toBeTruthy()

    rerender(<Avatar name="alice" url={null} online={false} />)
    expect(screen.queryByRole('img', { name: 'オンライン' })).toBeNull()
  })
})
