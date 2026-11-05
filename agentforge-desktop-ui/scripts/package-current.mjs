import { spawnSync } from 'node:child_process'

let target
if (process.platform === 'darwin') target = process.arch === 'arm64' ? 'mac-arm64' : 'mac-x64'
else if (process.platform === 'win32') target = 'win-x64'
else throw new Error(`Unsupported platform: ${process.platform}-${process.arch}`)

console.log(`[AgentForge] detected package target: ${target}`)
const result = spawnSync(process.execPath, ['scripts/package-desktop.mjs', target], { stdio: 'inherit' })
process.exit(result.status ?? 0)
