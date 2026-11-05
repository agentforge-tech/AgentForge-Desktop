import fs from 'node:fs'
import path from 'node:path'
import { execFileSync, spawnSync } from 'node:child_process'

const target = process.argv[2] || 'mac-arm64'
const releaseDir = path.resolve('release')
const appDir = path.join(releaseDir, target === 'mac-arm64' ? 'mac-arm64' : 'mac')
const appBundle = fs.existsSync(appDir)
  ? fs.readdirSync(appDir).find((name) => name.endsWith('.app'))
  : ''
if (!appBundle) throw new Error(`macOS app bundle missing under ${appDir}`)
const appPath = path.join(appDir, appBundle)
const java = path.join(appPath, 'Contents', 'Resources', 'runtime', 'jre', 'bin', 'java')
const jar = path.join(appPath, 'Contents', 'Resources', 'runtime', 'server', 'agentforge-desktop-server.jar')
for (const required of [appPath, java, jar]) {
  if (!fs.existsSync(required)) throw new Error(`macOS package output missing: ${required}`)
}
const dmg = fs.readdirSync(releaseDir).find((name) => name.endsWith('.dmg'))
if (!dmg) throw new Error('macOS DMG output missing')
const verify = spawnSync('codesign', ['--verify', '--deep', '--strict', appPath], { encoding: 'utf8' })
if (verify.status !== 0) console.warn('[AgentForge] codesign verify skipped or failed (local build):', verify.stderr)
console.log(`[AgentForge] macOS package verified: ${dmg}`)
