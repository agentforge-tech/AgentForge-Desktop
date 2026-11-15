import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { resolveFromUi, runtimeDir } from '../lib/paths.mjs'

const configPath = resolveFromUi(process.env.AGENTFORGE_PACKAGING_CONFIG || 'packaging.config.local.json')
const cacheDir = resolveFromUi('.cache', 'windows-jre')
const archive = path.join(cacheDir, 'jre8-win-x64.zip')
const extracted = path.join(cacheDir, 'extracted')
const output = path.join(runtimeDir, 'jre')
const url = process.env.AGENTFORGE_WINDOWS_JRE_URL
  || 'https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk'

const expand = (value) => value
  ? path.resolve(String(value).replace(/^~(?=$|[\\/])/, os.homedir()))
  : ''

let localConfig = {}
if (fs.existsSync(configPath)) {
  localConfig = JSON.parse(await fsp.readFile(configPath, 'utf8'))
}
const configuredHome = expand(
  process.env.AGENTFORGE_DESKTOP_JRE_HOME_WIN_X64 || localConfig?.jreHomes?.['win-x64']
)
const configuredArchive = expand(
  process.env.AGENTFORGE_WINDOWS_JRE_ARCHIVE || localConfig?.jreArchives?.['win-x64']
)

const isWinHome = (candidate) => candidate
  && fs.existsSync(path.join(candidate, 'bin', 'java.exe'))
  && fs.existsSync(path.join(candidate, 'release'))

function findHome(root) {
  if (isWinHome(root)) return root
  if (!fs.existsSync(root)) return ''
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const candidate = path.join(root, entry.name)
    if (isWinHome(candidate)) return candidate
  }
  return ''
}

async function download(destination) {
  console.log(`[AgentForge] downloading Windows x64 JRE 8: ${url}`)
  const response = await fetch(url, { redirect: 'follow' })
  if (!response.ok || !response.body) {
    throw new Error(`Windows JRE download failed: HTTP ${response.status}`)
  }
  await fsp.mkdir(path.dirname(destination), { recursive: true })
  const temp = `${destination}.download`
  const file = fs.createWriteStream(temp)
  try {
    await new Promise((resolve, reject) => {
      const reader = response.body.getReader()
      const pump = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read()
            if (done) break
            if (!file.write(Buffer.from(value))) await new Promise((ready) => file.once('drain', ready))
          }
          file.end(resolve)
        } catch (error) {
          file.destroy()
          reject(error)
        }
      }
      pump()
    })
    await fsp.rename(temp, destination)
  } catch (error) {
    await fsp.rm(temp, { force: true })
    throw error
  }
}

let runtimeHome = configuredHome
if (!runtimeHome) {
  let archivePath = configuredArchive
  if (!archivePath) {
    archivePath = archive
    if (!fs.existsSync(archivePath)) await download(archivePath)
  }
  await fsp.rm(extracted, { recursive: true, force: true })
  await fsp.mkdir(extracted, { recursive: true })
  const unzip = spawnSync('unzip', ['-q', '-o', archivePath, '-d', extracted], { stdio: 'inherit' })
  if (unzip.status !== 0) throw new Error(`Unable to extract Windows JRE: ${archivePath}`)
  runtimeHome = findHome(extracted)
  if (!runtimeHome) throw new Error(`Archive does not contain a Windows JRE: ${archivePath}`)
}

const release = await fsp.readFile(path.join(runtimeHome, 'release'), 'utf8')
if (!/JAVA_VERSION="1\.8/.test(release)) throw new Error(`Windows JRE must be 1.8: ${runtimeHome}`)
if (!/OS_ARCH="(?:amd64|x86_64)"/i.test(release)) throw new Error(`Windows JRE must be x64: ${runtimeHome}`)

await fsp.rm(output, { recursive: true, force: true })
await fsp.mkdir(path.dirname(output), { recursive: true })
await fsp.cp(runtimeHome, output, { recursive: true })
console.log(`[AgentForge] embedded Windows x64 JRE 8 prepared: ${output}`)