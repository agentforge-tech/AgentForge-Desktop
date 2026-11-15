import fs from 'node:fs'
import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { uiDir } from './paths.mjs'

export function existsCommand(value) {
  if (!value) return false
  if (value.includes(path.sep) || path.isAbsolute(value)) return fs.existsSync(value)
  return spawnSync(process.platform === 'win32' ? 'where' : 'which', [value], { stdio: 'ignore' }).status === 0
}

export function findCommand(candidates) {
  return candidates.filter(Boolean).find(existsCommand)
}

export function run(command, args, options = {}) {
  console.log(`\n> ${command} ${args.join(' ')}`)
  const result = spawnSync(command, args, { stdio: 'inherit', cwd: uiDir, ...options })
  if (result.status !== 0) process.exit(result.status ?? 1)
  return result
}

export function runNode(args, options = {}) {
  return run(process.execPath, args, options)
}

export function spawnNodeModule(relativeBin, args, options = {}) {
  return spawn(process.execPath, [path.resolve(uiDir, relativeBin), ...args], {
    stdio: 'inherit',
    cwd: uiDir,
    ...options
  })
}