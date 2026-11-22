import fs from 'node:fs'
import path from 'node:path'
import { fromUi, uiDir } from '../lib/paths.mjs'
import { run } from '../lib/exec.mjs'
import { loadPackageConfig } from '../lib/package-config.mjs'

// 1、解析打包目标并校验当前平台是否支持。
const target = process.argv[2]
const cross = process.argv.includes('--cross')
const targets = {
  'mac-arm64': {
    platform: 'darwin',
    electron: ['--mac', 'dmg', 'zip', '--arm64', '--config.artifactName=${productName}-${version}-mac-apple-silicon-arm64.${ext}']
  },
  'mac-x64': {
    platform: 'darwin',
    electron: ['--mac', 'dmg', 'zip', '--x64', '--config.artifactName=${productName}-${version}-mac-intel-x64.${ext}']
  },
  'win-x64': {
    platform: 'win32',
    electron: ['--win', 'nsis', '--x64', '--config.artifactName=${productName}-${version}-win-x64.${ext}']
  }
}
const spec = targets[target]
if (!spec) throw new Error(`Usage: node scripts/package/package-desktop.mjs ${Object.keys(targets).join('|')}`)
if (process.platform !== spec.platform && !(cross && target === 'win-x64' && process.platform === 'darwin')) {
  throw new Error(`${target} should be packaged on ${spec.platform}. Use --cross only for win-x64 from macOS.`)
}
if (cross && target !== 'win-x64') throw new Error('--cross is supported only for win-x64')

// 2、提前读取并校验应用名称和图标配置。
const { appLogo, appName } = loadPackageConfig()
console.log(`[AgentForge] package name: ${appName}`)
console.log(`[AgentForge] package icon: ${appLogo}`)

// 3、构建服务端，准备 JRE 和服务端资源，再构建前端页面。
run(process.execPath, [fromUi('scripts', 'build', 'build-server.mjs')])
run(process.execPath, [fromUi('scripts', 'build', 'prepare-server.mjs')])
if (target === 'win-x64' && process.platform !== 'win32') {
  run(process.execPath, [fromUi('scripts', 'package', 'check-windows-cross-env.mjs')])
  run(process.execPath, [fromUi('scripts', 'build', 'prepare-windows-jre.mjs')])
} else {
  run(process.execPath, [fromUi('scripts', 'build', 'prepare-jre.mjs'), target])
}
run(process.execPath, [fromUi('node_modules', 'vite', 'bin', 'vite.js'), 'build'])

// 4、检查打包所需运行时文件，并确认跨平台打包能力。
const java = path.join(uiDir, 'runtime', 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
const jar = path.join(uiDir, 'runtime', 'server', 'agentforge-desktop-server.jar')
if (!fs.existsSync(java)) throw new Error(`Embedded JRE missing: ${java}`)
if (!fs.existsSync(jar)) throw new Error(`Embedded server jar missing: ${jar}`)

if (cross && process.platform === 'darwin') {
  console.error('[AgentForge] Windows cross packaging is not enabled in this initial version.')
  console.error('Build on Windows x64 with: npm run package:win-x64')
  process.exit(2)
}

// 5、传入应用名称与图标，生成目标平台的桌面安装包。
const electronDist = path.join(uiDir, 'node_modules', 'electron', 'dist')
run(process.execPath, [
  path.join(uiDir, 'node_modules', 'electron-builder', 'out', 'cli', 'cli.js'),
  ...spec.electron,
  `--config.productName=${appName}`,
  `--config.${target.startsWith('mac-') ? 'mac' : 'win'}.icon=${appLogo}`,
  `--config.electronDist=${electronDist}`
])
// 6、检查目标平台的应用和运行时产物。
console.log(`[AgentForge] package completed: ${target}`)
if (target.startsWith('mac-')) run(process.execPath, [fromUi('scripts', 'verify', 'verify-mac-package.mjs'), target])
if (target === 'win-x64') run(process.execPath, [fromUi('scripts', 'verify', 'verify-win-package.mjs')])