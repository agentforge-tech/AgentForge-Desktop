import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const target = process.argv[2] || 'mac-arm64'
const targets = {
  'mac-arm64': { platform: 'darwin', arch: 'arm64', env: 'AGENTFORGE_DESKTOP_JRE_HOME_MAC_ARM64' },
  'mac-x64': { platform: 'darwin', arch: 'x64', env: 'AGENTFORGE_DESKTOP_JRE_HOME_MAC_X64' },
  'win-x64': { platform: 'win32', arch: 'x64', env: 'AGENTFORGE_DESKTOP_JRE_HOME_WIN_X64' }
}
const spec = targets[target]
if (!spec) throw new Error(`Usage: node scripts/prepare-jre.mjs ${Object.keys(targets).join('|')}`)

const expand = (value) => value
  ? path.resolve(String(value).replace(/^~(?=$|[\\/])/, os.homedir()))
  : ''

const configPath = path.resolve(process.env.AGENTFORGE_PACKAGING_CONFIG || 'packaging.config.local.json')
let localConfig = {}
if (fs.existsSync(configPath)) {
  localConfig = JSON.parse(await fsp.readFile(configPath, 'utf8'))
}

const javaName = spec.platform === 'win32' ? 'java.exe' : 'java'

function javaBinary(home) {
  if (!home) return ''
  const nested = path.join(home, 'jre', 'bin', javaName)
  if (fs.existsSync(nested)) return nested
  const direct = path.join(home, 'bin', javaName)
  return fs.existsSync(direct) ? direct : ''
}

function isJava8(home) {
  const java = javaBinary(home)
  if (!java) return false
  const info = spawnSync(java, ['-version'], { encoding: 'utf8' })
  return /version "1\.8/.test(`${info.stdout || ''}${info.stderr || ''}`)
}

const macFallbacks = spec.platform === 'darwin'
  ? [
      '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home',
      '/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home',
      '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home'
    ]
  : []
const candidates = [
  process.env[spec.env],
  localConfig?.jreHomes?.[target],
  process.env.AGENTFORGE_DESKTOP_JAVA_HOME,
  process.env.JAVA_HOME,
  ...macFallbacks
].map(expand).filter(Boolean)
const base = candidates.find(isJava8)
if (!base) {
  throw new Error(`No JRE 8 found for ${target}. Set ${spec.env} or packaging.config.local.json jreHomes.${target}`)
}

const nestedJre = path.join(base, 'jre', 'bin', javaName)
const jreHome = fs.existsSync(nestedJre) ? path.join(base, 'jre') : base
const java = javaBinary(jreHome)
if (!java) throw new Error(`JRE 8 not found for ${target}: ${jreHome}`)

const info = spawnSync(java, ['-version'], { encoding: 'utf8' })
const versionText = `${info.stdout || ''}${info.stderr || ''}`

if (spec.platform === 'darwin') {
  const file = spawnSync('file', [java], { encoding: 'utf8' })
  const text = file.stdout || ''
  if (spec.arch === 'arm64' && !/arm64/.test(text)) {
    throw new Error(`JRE architecture mismatch for mac-arm64: ${text.trim()}`)
  }
  if (spec.arch === 'x64' && !/x86_64/.test(text)) {
    throw new Error(`JRE architecture mismatch for mac-x64: ${text.trim()}`)
  }
}

const output = path.resolve('runtime', 'jre')
await fsp.rm(output, { recursive: true, force: true })
await fsp.mkdir(path.dirname(output), { recursive: true })
await fsp.cp(jreHome, output, { recursive: true })
console.log(`[AgentForge] embedded JRE 8 prepared for ${target}: ${output}`)
