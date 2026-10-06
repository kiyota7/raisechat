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
})
