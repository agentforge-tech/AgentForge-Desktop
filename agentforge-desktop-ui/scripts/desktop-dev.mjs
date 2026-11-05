import path from 'node:path'
import { spawn } from 'node:child_process'
import { findFreePort, spawnNodeModule, waitForHttp, shutdown } from './dev-utils.mjs'

const host = '127.0.0.1'
const embedded = process.argv.includes('--embedded') || process.env.AGENTFORGE_DESKTOP_EMBEDDED === '1'
const localServerPort = Number(process.env.AGENTFORGE_DESKTOP_SERVER_PORT) || 43179
const serverUrl = (process.env.AGENTFORGE_DESKTOP_SERVER_URL || (embedded ? '' : `http://${host}:${localServerPort}`))
  .replace(/\/$/, '')
const rootDir = process.env.AGENTFORGE_DESKTOP_ROOT_DIR || path.resolve('.agentforge-dev')
const port = await findFreePort(Number(process.env.AGENTFORGE_VITE_PORT) || 1420, host)
const rendererUrl = `http://${host}:${port}`
const children = []

console.log(`[AgentForge] renderer dev port: ${port}`)
console.log(`[AgentForge] desktop root dir: ${rootDir}`)
if (serverUrl) {
  console.log(`[AgentForge] attach Java runtime: ${serverUrl}`)
  console.log('[AgentForge] debug mode: backend is expected to be started separately by IDE or shell')
} else {
  console.log('[AgentForge] Electron will start runtime/server/agentforge-desktop-server.jar automatically')
}

const vite = spawnNodeModule('node_modules/vite/bin/vite.js', ['--host', host, '--port', String(port), '--strictPort'], {
  env: { ...process.env, AGENTFORGE_VITE_PORT: String(port) }
})
children.push(vite)

try {
  await waitForHttp(rendererUrl)
  const { default: electronPath } = await import('electron')
  const electron = spawn(electronPath, ['.'], {
    cwd: process.cwd(),
    stdio: 'inherit',
    env: {
      ...process.env,
      ELECTRON_RENDERER_URL: rendererUrl,
      AGENTFORGE_DESKTOP_SERVER_URL: serverUrl,
      AGENTFORGE_DESKTOP_ROOT_DIR: rootDir
    }
  })
  children.push(electron)
  electron.once('exit', (code) => {
    shutdown(children.filter((child) => child !== electron))
    process.exit(code ?? 0)
  })
  vite.once('exit', (code) => {
    if (code && code !== 0) {
      shutdown(children.filter((child) => child !== vite))
      process.exit(code)
    }
  })
} catch (error) {
  shutdown(children)
  console.error('[AgentForge] desktop dev startup failed:', error)
  process.exit(1)
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    shutdown(children)
    process.exit(0)
  })
}
