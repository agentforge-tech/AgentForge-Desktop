import fs from 'node:fs'
import path from 'node:path'
import { agentforgeHome, serverDir } from '../lib/paths.mjs'
import { run } from '../lib/exec.mjs'
import { findMaven, localRepository, requiredArtifacts } from '../lib/maven.mjs'
import { findJavaHome } from '../lib/java.mjs'

const desktopHome = agentforgeHome()

const mvn = findMaven()
if (!mvn) {
  throw new Error('Maven not found. Set AGENTFORGE_MAVEN or install Maven 3.9+.')
}

const javaHome = findJavaHome()
if (!javaHome) {
  throw new Error('JDK 8 not found. Set AGENTFORGE_DESKTOP_JAVA_HOME to a JDK 8 home.')
}
console.log(`[AgentForge] JAVA_HOME=${javaHome}`)
console.log(`[AgentForge] Maven=${mvn}`)

const env = {
  ...process.env,
  JAVA_HOME: javaHome,
  PATH: `${path.join(javaHome, 'bin')}${path.delimiter}${process.env.PATH || ''}`
}

const repo = localRepository(mvn, env)
const artifacts = requiredArtifacts(repo)
const missing = artifacts.filter((file) => !fs.existsSync(file))
const coreMissing = missing.length > 0
if (coreMissing || process.env.AGENTFORGE_REBUILD_CORE === '1') {
  if (!fs.existsSync(desktopHome)) {
    throw new Error(`AgentForge source not found: ${desktopHome}. Set AGENTFORGE_HOME or run AgentForge core install manually.`)
  }
  console.log(`[AgentForge] installing core artifacts from ${desktopHome}`)
  run(mvn, [
    '-f', path.join(desktopHome, 'pom.xml'),
    '-pl', ':agentforge-ai-bom,:agentforge-model-api,:agentforge-model-core,:agentforge-model-openai,:agentforge-model-anthropic,:agentforge-model-registry,:agentforge-agent-core,:agentforge-harness-agent,:agentforge-service-protocal',
    '-am', 'install', '-DskipTests', '-Dspotless.check.skip=true', '-Dspotless.apply.skip=true'
  ], { env })
} else {
  console.log(`[AgentForge] required artifacts found: ${artifacts.length}`)
}

run(mvn, [
  '-f', path.join(serverDir, 'pom.xml'),
  'clean',
  'package', '-DskipTests'
], { env })
console.log('[AgentForge] server jar build completed')