import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

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
if (!spec) throw new Error(`Usage: node scripts/package-desktop.mjs ${Object.keys(targets).join('|')}`)
if (process.platform !== spec.platform && !(cross && target === 'win-x64' && process.platform === 'darwin')) {
  throw new Error(`${target} should be packaged on ${spec.platform}. Use --cross only for win-x64 from macOS.`)
}
if (cross && target !== 'win-x64') throw new Error('--cross is supported only for win-x64')

const run = (command, args, options = {}) => {
  console.log(`\n> ${command} ${args.join(' ')}`)
  const result = spawnSync(command, args, { stdio: 'inherit', ...options })
  if (result.status !== 0) process.exit(result.status ?? 1)
}

run(process.execPath, ['scripts/build-server.mjs'])
run(process.execPath, ['scripts/prepare-server.mjs'])
if (target === 'win-x64' && process.platform !== 'win32') {
  run(process.execPath, ['scripts/check-windows-cross-env.mjs'])
  run(process.execPath, ['scripts/prepare-windows-jre.mjs'])
} else {
  run(process.execPath, ['scripts/prepare-jre.mjs', target])
}
run(process.execPath, ['node_modules/vite/bin/vite.js', 'build'])

const java = path.resolve('runtime', 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
const jar = path.resolve('runtime', 'server', 'agentforge-desktop-server.jar')
if (!fs.existsSync(java)) throw new Error(`Embedded JRE missing: ${java}`)
if (!fs.existsSync(jar)) throw new Error(`Embedded server jar missing: ${jar}`)

if (cross && process.platform === 'darwin') {
  console.error('[AgentForge] Windows cross packaging is not enabled in this initial version.')
  console.error('Build on Windows x64 with: npm run package:win-x64')
  process.exit(2)
}

const electronDist = path.resolve('node_modules', 'electron', 'dist')
run(process.execPath, [
  'node_modules/electron-builder/out/cli/cli.js',
  ...spec.electron,
  `--config.electronDist=${electronDist}`
])
console.log(`[AgentForge] package completed: ${target}`)
if (target.startsWith('mac-')) run(process.execPath, ['scripts/verify-mac-package.mjs', target])
if (target === 'win-x64') run(process.execPath, ['scripts/verify-win-package.mjs'])
