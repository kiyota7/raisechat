import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Login } from './Login'
import type { User } from './types'

const mocks = vi.hoisted(() => ({ post: vi.fn(), setToken: vi.fn() }))
vi.mock('./api', () => ({ api: { post: mocks.post }, setToken: mocks.setToken }))

const user: User = { id: 1, username: 'alice', displayName: 'alice', status: '', avatarUrl: null }

async function fillAndSubmit(u: ReturnType<typeof userEvent.setup>, button: string) {
  await u.type(screen.getByLabelText('ユーザーID'), 'alice')
  await u.type(screen.getByLabelText('パスワード'), 'Chat-Test-1x')
  await u.click(screen.getByRole('button', { name: button }))
}

describe('Login', () => {
  beforeEach(() => {
    mocks.post.mockReset()
    mocks.setToken.mockReset()
  })

  it('新規登録の画面にだけ、パスワードの条件を表示する', async () => {
    const u = userEvent.setup()
    render(<Login onLogin={() => {}} />)
    expect(screen.queryByText(/よくある簡単なパスワード/)).toBeNull()

    await u.click(screen.getByRole('button', { name: 'アカウントをお持ちでない方はこちら' }))
    expect(screen.getByText(/ユーザーIDを含むものや、よくある簡単なパスワード/)).toBeTruthy()

    await u.click(screen.getByRole('button', { name: 'ログインはこちら' }))
    expect(screen.queryByText(/よくある簡単なパスワード/)).toBeNull()
  })

  it('ログインに成功したら、トークンを保存してユーザーを渡す', async () => {
    mocks.post.mockResolvedValue({ token: 'tok', user })
    const onLogin = vi.fn()
    const u = userEvent.setup()
    render(<Login onLogin={onLogin} />)
    await fillAndSubmit(u, 'ログイン')

    expect(mocks.post).toHaveBeenCalledWith('/auth/login', { username: 'alice', password: 'Chat-Test-1x' })
    expect(mocks.setToken).toHaveBeenCalledWith('tok')
    expect(onLogin).toHaveBeenCalledWith(user)
  })

  it('サーバーのエラーメッセージを、そのまま表示する', async () => {
    mocks.post.mockRejectedValue(new Error('ユーザーIDまたはパスワードが正しくありません'))
    const u = userEvent.setup()
    render(<Login onLogin={() => {}} />)
    await fillAndSubmit(u, 'ログイン')

    expect((await screen.findByRole('alert')).textContent).toContain('正しくありません')
    expect(mocks.setToken).not.toHaveBeenCalled()
  })

  it('弱いパスワードで登録しようとしたときの、サーバーのメッセージを表示する', async () => {
    mocks.post.mockRejectedValue(new Error('よくあるパスワードは使えません。別のパスワードにしてください'))
    const u = userEvent.setup()
    render(<Login onLogin={() => {}} />)
    await u.click(screen.getByRole('button', { name: 'アカウントをお持ちでない方はこちら' }))
    await fillAndSubmit(u, '登録してはじめる')

    expect((await screen.findByRole('alert')).textContent).toContain('よくあるパスワードは使えません')
    expect(mocks.post).toHaveBeenCalledWith('/auth/register', { username: 'alice', password: 'Chat-Test-1x', displayName: '' })
  })
})
