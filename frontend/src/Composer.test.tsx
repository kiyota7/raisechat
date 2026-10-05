import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from './Composer'

describe('Composer の入力中通知', () => {
  beforeEach(() => {
    // RTLはVitestの偽の時計を検知できないため、実時間でも進める(待ち続けて固まるのを避ける)
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const setup = () => {
    const onTyping = vi.fn()
    const user = userEvent.setup({ delay: null })
    render(<Composer placeholder="入力" members={[]} onSend={async () => {}} onTyping={onTyping} />)
    return { onTyping, user, box: screen.getByPlaceholderText('入力') }
  }

  it('連続して入力しても、2秒間は1回にまとめる', async () => {
    const { onTyping, user, box } = setup()
    await user.type(box, 'hello world')
    expect(onTyping).toHaveBeenCalledTimes(1)
  })

  it('2秒たってから入力を続けると、もう一度通知する', async () => {
    const { onTyping, user, box } = setup()
    await user.type(box, 'abc')
    expect(onTyping).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(2100)
    await user.type(box, 'def')
    expect(onTyping).toHaveBeenCalledTimes(2)
  })

  it('空白だけの入力や、入力を消したときは通知しない', async () => {
    const { onTyping, user, box } = setup()
    await user.type(box, '   ')
    expect(onTyping).not.toHaveBeenCalled()
  })
})
