import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { runtimeDir, resolveFromUi } from '../lib/paths.mjs'

/**
 * 按平台准备内置 ripgrep 二进制：下载官方 release → 校验 sha256 → 解压 → 落到 runtime/tools/rg/<target>/。
 * 该产物随安装包分发（package.json 的 extraResources），首次启动由主进程静默复制到用户级目录。
 *
 * 用法：node scripts/build/prepare-rg.mjs [mac-arm64|mac-x64|win-x64]
 * 已存在的产物默认复用；传 --force 重新下载。
 */

const targets = {
  'mac-arm64': { platform: 'darwin', arch: 'arm64', asset: 'aarch64-apple-darwin' },
  'mac-x64': { platform: 'darwin', arch: 'x64', asset: 'x86_64-apple-darwin' },
  'win-x64': { platform: 'win32', arch: 'x64', asset: 'x86_64-pc-windows-msvc' }
}

// 与 electron/enhancements.mjs 保持同一份钉版本信息，避免打包产物与运行期校验漂移。
const RG_VERSION = '16.0.0'
const SHA256 = {
  'mac-arm64': '5a145656d5067144001bfce6648767fd3254599aa736f1ffdb7fb907760b8ca1',
  'mac-x64': 'aa9183b0e0bfac03c5818895ad18b75001c402d230063898abfe5a58cd413caa',
  'win-x64': 'f418ae83e63046a94a7b5f3a44bf3a169f66e689164856ecee02547131c73931'
}

const args = process.argv.slice(2)
const force = args.includes('--force')
const requested = args.find((a) => !a.startsWith('--')) || detectHostTarget()

function detectHostTarget() {
  if (process.platform === 'darwin') return process.arch === 'arm64' ? 'mac-arm64' : 'mac-x64'
  if (process.platform === 'win32') return 'win-x64'
  return ''
}

const target = targets[requested]
if (!target) {
  throw new Error(`Usage: node scripts/build/prepare-rg.mjs ${Object.keys(targets).join('|')} [--force]`)
}

const binName = target.platform === 'win32' ? 'rg.exe' : 'rg'
const archiveName = `ripgrep-${RG_VERSION}-${target.asset}${target.platform === 'win32' ? '.zip' : '.tar.gz'}`
const url = `https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}/${archiveName}`
const cacheDir = resolveFromUi('.cache', 'ripgrep')
const archive = path.join(cacheDir, archiveName)
const output = path.join(runtimeDir, 'tools', 'rg', requested)
const outputBin = path.join(output, binName)
const expectedSha = SHA256[requested]

function sha256File(file) {
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

async function download(destination) {
  console.log(`[AgentForge] downloading ripgrep ${RG_VERSION} (${requested}): ${url}`)
  const response = await fetch(url, { redirect: 'follow' })
  if (!response.ok || !response.body) {
    throw new Error(`ripgrep download failed: HTTP ${response.status}`)
  }
  const buffer = Buffer.from(await response.arrayBuffer())
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.writeFileSync(destination, buffer)
}

function findBinary(root, name) {
  const stack = [root]
  while (stack.length) {
    const current = stack.pop()
    let entries = []
    try {
      entries = fs.readdirSync(current, { withFileTypes: true })
    } catch (_error) {
      continue
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name)
      if (entry.isFile() && entry.name === name) return full
      if (entry.isDirectory()) stack.push(full)
    }
  }
  return ''
}

// 1、准备产物：已有且校验一致时直接跳过，保证可重复执行。
if (!force && fs.existsSync(outputBin)) {
  console.log(`[AgentForge] embedded ripgrep already prepared (${requested}): ${outputBin}`)
  process.exit(0)
}

// 2、下载并校验压缩包（校验失败立即中止，不静默降级）。
if (force || !fs.existsSync(archive)) {
  await download(archive)
}
const archiveSha = sha256File(archive)
if (archiveSha !== expectedSha) {
  await fsp.rm(archive, { force: true })
  throw new Error(`ripgrep archive checksum mismatch for ${requested}: expected ${expectedSha}, got ${archiveSha}`)
}

// 3、解压到临时目录，只取二进制，避免把 doc/complete 等一并打进安装包。
const staging = path.join(cacheDir, `staging-${requested}`)
await fsp.rm(staging, { recursive: true, force: true })
await fsp.mkdir(staging, { recursive: true })
const untar = spawnSync('tar', ['-xf', archive, '-C', staging], { encoding: 'utf8' })
if (untar.status !== 0) {
  throw new Error(`ripgrep extract failed: ${(untar.stderr || untar.stdout || '').trim()}`)
}

const sourceBin = findBinary(staging, binName)
if (!sourceBin) throw new Error(`rg binary not found in ${archive}`)

// 4、架构自检：避免把错架构的二进制打进安装包（等价 prepare-jre 的做法）。
if (target.platform === 'darwin') {
  const file = spawnSync('file', [sourceBin], { encoding: 'utf8' })
  const text = file.stdout || ''
  if (target.arch === 'arm64' && !/arm64/.test(text)) {
    throw new Error(`ripgrep architecture mismatch for ${requested}: ${text.trim()}`)
  }
  if (target.arch === 'x64' && !/x86_64/.test(text)) {
    throw new Error(`ripgrep architecture mismatch for ${requested}: ${text.trim()}`)
  }
}

// 5、落盘并写 manifest（运行期据此校验二进制完整性）。
await fsp.rm(output, { recursive: true, force: true })
await fsp.mkdir(output, { recursive: true })
await fsp.copyFile(sourceBin, outputBin)
if (target.platform !== 'win32') await fsp.chmod(outputBin, 0o755)

const binSha256 = sha256File(outputBin)
const probe = spawnSync(outputBin, ['--version'], { encoding: 'utf8' })
const versionLine = `${probe.stdout || ''}`.trim().split(/\r?\n/)[0]
if (probe.status !== 0) {
  throw new Error(`prepared rg binary is not executable: ${(probe.stderr || '').trim()}`)
}

await fsp.writeFile(
  path.join(output, 'manifest.json'),
  `${JSON.stringify({ version: RG_VERSION, target: requested, asset: archiveName, binSha256, preparedAt: new Date().toISOString() }, null, 2)}\n`,
  'utf8'
)
await fsp.rm(staging, { recursive: true, force: true })

console.log(`[AgentForge] embedded ripgrep ${RG_VERSION} prepared for ${requested}: ${outputBin}`)
console.log(`[AgentForge] ${versionLine}`)