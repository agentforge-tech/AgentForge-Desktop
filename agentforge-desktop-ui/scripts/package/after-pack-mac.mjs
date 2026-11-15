import { execFileSync } from 'node:child_process'

export default async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return
  const appPath = `${context.appOutDir}/${context.packager.appInfo.productFilename}.app`
  try {
    execFileSync('xattr', ['-cr', appPath], { stdio: 'ignore' })
  } catch {
    console.warn('[AgentForge] some extended attributes could not be removed')
  }
  try {
    execFileSync('codesign', ['--force', '--deep', '--sign', '-', '--timestamp=none', appPath], { stdio: 'inherit' })
  } catch (error) {
    console.warn('[AgentForge] ad-hoc code signing failed:', error.message)
  }
}
