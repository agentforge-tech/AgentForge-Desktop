import fs from 'node:fs/promises'
import path from 'node:path'

const uiDir = path.resolve(process.cwd())
const source = process.env.AGENTFORGE_DESKTOP_SERVER_JAR_SOURCE
  || path.resolve(uiDir, '..', 'agentforge-desktop-server', 'target', 'agentforge-desktop-server.jar')
const targetDir = path.join(uiDir, 'runtime', 'server')
const target = path.join(targetDir, 'agentforge-desktop-server.jar')

try {
  await fs.access(source)
} catch {
  throw new Error(`Java backend jar not found: ${source}\nBuild it first with: npm run build:server`)
}
await fs.mkdir(targetDir, { recursive: true })
await fs.copyFile(source, target)
console.log(`[AgentForge] server runtime prepared: ${target}`)
