import { describe, expect, it } from 'vitest'
import { formatTime } from './format'

describe('formatTime', () => {
  it('当日のメッセージは時刻だけを表示する', () => {
    expect(formatTime(new Date().toISOString())).toMatch(/^\d{1,2}:\d{2}$/)
  })

  it('当日以外は日付つきで表示する', () => {
    const twoDaysAgo = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString()
    expect(formatTime(twoDaysAgo)).toMatch(/\d{4}\/\d{1,2}\/\d{1,2} \d{1,2}:\d{2}/)
  })
})
