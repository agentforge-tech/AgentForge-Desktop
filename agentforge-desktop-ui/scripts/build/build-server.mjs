import fs from 'node:fs'
import path from 'node:path'
import { agentforgeHome, serverDir } from '../lib/paths.mjs'
import { run } from '../lib/exec.mjs'
import { findMaven, localRepository, coreArtifact } from '../lib/maven.mjs'
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
const coreJar = coreArtifact(repo)
const coreMissing = !fs.existsSync(coreJar)
if (coreMissing || process.env.AGENTFORGE_REBUILD_CORE === '1') {
  if (!fs.existsSync(desktopHome)) {
    throw new Error(`AgentForge source not found: ${desktopHome}. Set AGENTFORGE_HOME or run AgentForge core install manually.`)
  }
  console.log(`[AgentForge] installing core artifacts from ${desktopHome}`)
  run(mvn, [
    '-f', path.join(desktopHome, 'pom.xml'),
    '-pl', ':agentforge-ai-bom,:agentforge-model-api,:agentforge-model-core,:agentforge-model-openai,:agentforge-model-anthropic,:agentforge-model-registry,:agentforge-agent-core',
    '-am', 'install', '-DskipTests', '-Dspotless.check.skip=true', '-Dspotless.apply.skip=true'
  ], { env })
} else {
  console.log(`[AgentForge] core artifacts found: ${coreJar}`)
}

run(mvn, [
  '-f', path.join(serverDir, 'pom.xml'),
  'clean',
  'package', '-DskipTests'
], { env })
console.log('[AgentForge] server jar build completed')