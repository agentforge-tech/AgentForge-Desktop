import fs from 'node:fs'
import path from 'node:path'
import { fromUi } from './paths.mjs'

export function loadPackageConfig(confDir = fromUi('scripts', 'conf')) {
  const configPath = path.join(confDir, 'package.json')
  let config = {}
  try {
    config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
  } catch (error) {
    if (error.code !== 'ENOENT') throw new Error(`Invalid package config ${configPath}: ${error.message}`)
  }
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    throw new Error(`Invalid package config ${configPath}: expected an object`)
  }
  const appName = config.appName === undefined || config.appName === '' ? 'AgentForge Desktop' : config.appName
  if (typeof appName !== 'string' || !appName.trim() || appName !== appName.trim() || /[<>:"/\\|?*\x00-\x1f]/.test(appName) || /\.$/.test(appName) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i.test(appName)) {
    throw new Error(`Invalid appName in ${configPath}: expected a valid cross-platform application name`)
  }
  const logo = config.appLogo === undefined || config.appLogo === '' ? 'assets/app-logo.png' : config.appLogo
  if (typeof logo !== 'string' || !logo.trim()) {
    throw new Error(`Invalid appLogo in ${configPath}: expected a PNG file path`)
  }
  const appLogo = path.resolve(confDir, logo)
  let data
  try {
    data = fs.readFileSync(appLogo)
  } catch (error) {
    throw new Error(`Cannot read appLogo ${appLogo} (configured in ${configPath}): ${error.message}`)
  }
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  if (path.extname(appLogo).toLowerCase() !== '.png' || data.length < 33 || !data.subarray(0, 8).equals(signature) || data.toString('ascii', 12, 16) !== 'IHDR') {
    throw new Error(`Invalid appLogo ${appLogo}: expected a PNG image`)
  }
  const width = data.readUInt32BE(16)
  const height = data.readUInt32BE(20)
  if (width !== height || width < 512) {
    throw new Error(`Invalid appLogo ${appLogo}: image must be square and at least 512 x 512 pixels`)
  }
  return { appLogo, appName }
}
