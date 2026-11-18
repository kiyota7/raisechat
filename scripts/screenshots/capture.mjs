// README用のスクリーンショットを docs/images/ に撮る。事前に scripts/seed-demo.sh でデモデータを入れておく。
// 使い方(scripts/screenshots で):
//   npm install
//   DEMO_PASSWORD='パスワード' BASE_URL='https://<IP>.sslip.io' npm run capture
// ログインは、seed-demo.sh と同じユーザー(raise_owner)。パスワードは環境変数で渡す(コードに書かない)。
// ブラウザは、手元にインストール済みの Google Chrome を使う(ブラウザのダウンロードは不要)。
import { chromium } from 'playwright'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const BASE = process.env.BASE_URL
const PASSWORD = process.env.DEMO_PASSWORD
const USER = process.env.DEMO_USER ?? 'raise_owner'
if (!BASE || !PASSWORD) {
  console.error('BASE_URL と DEMO_PASSWORD を環境変数で指定してください')
  process.exit(1)
}
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../docs/images')

const browser = await chromium.launch({ channel: 'chrome' })

async function login(page) {
  await page.goto(BASE)
  await page.getByPlaceholder('半角英数字とアンダースコア(3〜20文字)').fill(USER)
  await page.locator('input[type=password]').fill(PASSWORD)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.getByText('# general').first().waitFor()
  await page.waitForTimeout(800)
}
const shot = (page, name) => page.screenshot({ path: path.join(OUT, name) })
const openChannel = async (page, name) => {
  await page.getByText(`# ${name}`, { exact: true }).first().click()
  await page.waitForTimeout(600)
}

// 画面幅1280x724、倍率1.25 → 1600x905(既存の画像と同じ大きさ)
const desktop = await browser.newContext({ viewport: { width: 1280, height: 724 }, deviceScaleFactor: 1.25, locale: 'ja-JP' })
const page = await desktop.newPage()
await login(page)

// 1. チャット
await openChannel(page, 'general')
await shot(page, 'chat.png')

// 2. スレッド(#dev の1件目にスレッド返信がある)
await openChannel(page, 'dev')
const msg = page.getByTestId('message').filter({ hasText: 'デプロイ手順書' }).first()
await msg.hover()
await msg.getByRole('button', { name: 'スレッドで返信' }).click()
await page.getByText('疎通確認は').first().waitFor()
await page.waitForTimeout(500)
await shot(page, 'thread.png')
await page.getByRole('button', { name: 'スレッドを閉じる' }).click()

// 3. 検索(「デザイン」。検索結果は、マークダウンを変換せずに表示されるので、記号を含まない語を選ぶ)
await openChannel(page, 'general')
const search = page.getByRole('textbox', { name: 'メッセージを検索' })
await search.fill('デザイン')
await search.press('Enter')
await page.getByText('の検索結果').waitFor()
await page.waitForTimeout(500)
await shot(page, 'search.png')
await search.fill('')

// 4. メンバー管理(オーナー)
await page.getByRole('button', { name: '閉じる', exact: true }).first().click().catch(() => {})
await page.getByRole('button', { name: 'ワークスペース', exact: true }).click()
await page.getByRole('dialog').waitFor()
await page.waitForTimeout(500)
await shot(page, 'members.png')
await desktop.close()

// 5. スマートフォン幅(390x844、倍率2 → 780x1688)
const mobile = await browser.newContext({
  viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ja-JP',
})
const m = await mobile.newPage()
await login(m)
await shot(m, 'mobile.png')

await browser.close()
console.log(`保存しました: ${OUT}`)
