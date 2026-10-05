import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
  localStorage.clear()
})

// jsdom には scrollIntoView がない(Chat が末尾へのスクロールに使う)
Element.prototype.scrollIntoView = () => {}
