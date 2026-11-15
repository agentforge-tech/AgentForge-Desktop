import { spawnSync } from 'node:child_process'

if (process.platform !== 'darwin') {
  throw new Error('Windows cross packaging is supported only from macOS.')
}
const wine = ['wine64', 'wine'].find((command) =>
  spawnSync(command, ['--version'], { stdio: 'ignore' }).status === 0
)
if (wine) {
  console.log(`[AgentForge] Wine detected: ${wine}`)
} else {
  const docker = spawnSync('docker', ['info'], { stdio: 'ignore' }).status === 0
  if (!docker) {
    throw new Error('Windows cross packaging requires Wine or a running Docker Desktop.')
  }
  console.log('[AgentForge] Docker detected')
}
console.log('[AgentForge] Note: this initial version recommends building win-x64 natively on Windows.')
