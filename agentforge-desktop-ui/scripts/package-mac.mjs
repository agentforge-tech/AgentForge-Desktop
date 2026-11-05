import { spawnSync } from 'node:child_process'

const args = process.argv.slice(2)
const archIndex = args.indexOf('--arch')
const requested = archIndex >= 0 ? args[archIndex + 1] : process.env.AGENTFORGE_MAC_ARCH
const normalized = String(requested || '').trim().toLowerCase()
const map = {
  arm64: 'mac-arm64',
  aarch64: 'mac-arm64',
  'apple-silicon': 'mac-arm64',
  intel: 'mac-x64',
  x64: 'mac-x64',
  x86_64: 'mac-x64'
}
const target = map[normalized]
if (!target) throw new Error('Usage: npm run package:mac -- --arch arm64|intel')
const result = spawnSync(process.execPath, ['scripts/package-desktop.mjs', target], { stdio: 'inherit' })
process.exit(result.status ?? 1)
