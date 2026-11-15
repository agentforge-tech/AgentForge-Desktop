import fs from 'node:fs/promises'
import path from 'node:path'
import { runtimeDir, serverDir } from '../lib/paths.mjs'

const source = process.env.AGENTFORGE_DESKTOP_SERVER_JAR_SOURCE
  || path.join(serverDir, 'target', 'agentforge-desktop-server.jar')
const targetDir = path.join(runtimeDir, 'server')
const target = path.join(targetDir, 'agentforge-desktop-server.jar')

try {
  await fs.access(source)
} catch {
  throw new Error(`Java backend jar not found: ${source}\nBuild it first with: npm run build:server`)
}
await fs.mkdir(targetDir, { recursive: true })
await fs.copyFile(source, target)
console.log(`[AgentForge] server runtime prepared: ${target}`)