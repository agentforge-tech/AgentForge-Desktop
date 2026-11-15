import fs from 'node:fs'
import path from 'node:path'
import { uiDir } from '../lib/paths.mjs'

const releaseDir = path.join(uiDir, 'release')
const unpacked = path.join(releaseDir, 'win-unpacked')
const java = path.join(unpacked, 'resources', 'runtime', 'jre', 'bin', 'java.exe')
const jar = path.join(unpacked, 'resources', 'runtime', 'server', 'agentforge-desktop-server.jar')
for (const required of [unpacked, java, jar]) {
  if (!fs.existsSync(required)) throw new Error(`Windows package output missing: ${required}`)
}
const header = fs.readFileSync(java)
if (header[0] !== 0x4d || header[1] !== 0x5a) throw new Error('Embedded java.exe is not a PE file')
const installer = fs.readdirSync(releaseDir).find((name) => /win-x64.*\.exe$/i.test(name))
if (!installer) throw new Error('Windows installer missing')
console.log(`[AgentForge] Windows package verified: ${installer}`)