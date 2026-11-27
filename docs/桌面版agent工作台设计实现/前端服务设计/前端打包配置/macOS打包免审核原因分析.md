# macOS 打包产物"安装不再被安全审核拦截"的原因分析

> 分析对象：`agentforge-desktop-ui` 工程执行 `npm run package:mac -- --arch arm64` 后的产物（`release/AgentForge Desktop-0.1.0-mac-apple-silicon-arm64.dmg` 及 `release/mac-arm64/AgentForge Desktop.app`，构建时间 2026-11-27 12:32）。
> 结论基于对源码、git 历史、electron-builder 内部路径与真实产物状态的核查，非推测。

---

## 1. 结论速览

当前产物双击安装、启动时不再弹出"无法验证开发者 / 已损坏，无法打开"之类的安全拦截，不是因为它"签名合规"了，而是两个原因叠加：

1. **Gatekeeper 的审核入口根本没有被触发** —— 本机构建出来的 DMG 和 App 全程不存在 `com.apple.quarantine` 隔离属性，而 Gatekeeper 只对带隔离属性的文件做评估。
2. **Apple Silicon 强制的"代码签名完整性"关卡被 afterPack 钩子补上了** —— `scripts/package/after-pack-mac.mjs:7` 清理扩展属性、`:12` 用 ad-hoc 身份对整包重签，重建了与内容一致的 bundle seal，使 `codesign -v --deep --strict` 能通过。

一句话：**从"签名坏了导致系统拒绝执行"变成了"签得自洽 + 没有隔离标记所以系统不审"。** ad-hoc 签名不具备任何可信度，它只解决"能不能跑"，不解决"能不能分发"。

---

## 2. 调用链路

```
npm run package:mac -- --arch arm64
  → scripts/package/package-mac.mjs        （解析 --arch，归一化为 mac-arm64）
  → scripts/package/package-desktop.mjs    （读 conf 配置 → 构建 server/JRE/前端 → 调 electron-builder）
  → electron-builder 24.13.3
      ├─ 打 .app（files + extraResources：dist、asar、runtime/server、runtime/jre、runtime/tools）
      ├─ afterPack: scripts/package/after-pack-mac.mjs   ← 关键
      ├─ electron-builder 自身 sign()（无证书 → 跳过，见 §4）
      └─ 生成 dmg / zip
  → scripts/verify/verify-mac-package.mjs  （校验 JRE/jar/dmg 存在，codesign 失败仅告警）
```

`package.json` 的 `build.afterPack` 把钩子挂在打包末尾。在 electron-builder 内部，`afterPack` 先于其自身的 `doSignAfterPack` 执行（`node_modules/app-builder-lib/out/macPackager.js:125-126`），所以这个钩子实际上"接管"了签名职责。

钩子只做两件事：

| 行号 | 动作 | 实际作用 |
| --- | --- | --- |
| `after-pack-mac.mjs:7` | `xattr -cr <app>` | 递归清空整包扩展属性，包含可能残留的 `com.apple.quarantine` |
| `after-pack-mac.mjs:12` | `codesign --force --deep --sign - --timestamp=none <app>` | 以 ad-hoc 身份（`-`）对整包重签，重建 bundle seal |

两处都被 `try/catch` 包裹，失败只 `console.warn` 不中断打包 —— 这意味着"打包成功"不等于"签名成功"。

---

## 3. 产物实测状态（决定行为的事实）

### 3.1 整包签名是 ad-hoc，不是开发者证书

`release/mac-arm64/AgentForge Desktop.app` 的签名信息：

- `Identifier = cloud.changlu.agentforge.desktop`
- `CodeDirectory flags = 0x2(adhoc)`，`Signature = adhoc`
- `TeamIdentifier = not set`
- 无 `flags = 0x10000(runtime)` → **未启用 hardened runtime**
- `Info.plist entries = 30`，`Sealed Resources version = 2 rules = 13 files = 135`

### 3.2 但签名是"自洽"的

- `codesign -v --verbose` → `valid on disk` / `satisfies its Designated Requirement`
- `codesign -v --deep --strict` → 无输出（通过）

这正是 Apple Silicon 的内核要求：每个 Mach-O 执行前必须有有效签名，且 bundle 内容的哈希要与 seal 一致。本工程把 `dist`、`asar`、`runtime/jre`、`runtime/tools` 塞进 `.app`，必然破坏 Electron 预置包的原始 seal；afterPack 的整包重签把这个 seal 重新建立起来。

### 3.3 内嵌 JRE 保留了原厂 Developer ID 签名

`Contents/Resources/runtime/jre/bin/java`：

- `Identifier = com.azul.zulu.java`
- `Authority = Developer ID Application: Azul Systems, Inc. (TDTHCUPYFR)` → `Developer ID Certification Authority` → `Apple Root CA`
- `flags = 0x10000(runtime)`，带时间戳

复现验证：单独执行同样的 `codesign --force --deep --sign -` 后，JRE 仍是 Azul 签名而非 ad-hoc。原因是 JRE 位于 `Resources/` 下，被当作资源而非嵌套代码处理，`--deep` 没有对它降级重签。这恰好避免了"把原厂签名/公证覆盖成 ad-hoc"这种更糟的结果，也是当前产物还能稳定启动的重要前提。

### 3.4 扩展属性：全程没有 quarantine

| 对象 | 属性 | 是否有 quarantine |
| --- | --- | --- |
| `release/mac-arm64/AgentForge Desktop.app` | `com.apple.provenance` | 否 |
| DMG 内挂载的 App | `com.apple.provenance` | 否 |
| DMG 文件本身 | `com.apple.FinderInfo`、`com.apple.lastuseddate#PS`、`com.apple.provenance` | 否 |

`com.apple.provenance` 是系统级溯源属性，受 SIP 保护，`xattr -cr` 删不掉，但它**不触发 Gatekeeper**。

### 3.5 Gatekeeper 是开着的，且明确判定当前产物不合格

- `spctl --status` → `assessments enabled`，`developer id enabled`
- `spctl -a -vvv "release/mac-arm64/AgentForge Desktop.app"` → **`rejected`**
- 对 DMG → **`rejected`**，`source = no usable signature`

也就是说：**系统认定这个包不合格，只是没有机会拒绝它** —— Gatekeeper 的拒绝判定只在文件带隔离属性时才被触发。

### 3.6 对照实验：加上隔离属性立刻被拦

把同一份 App 复制出来，人为写入 `com.apple.quarantine = 0081;00000000;Safari;`：

- 写入后 `spctl -a -vvv` → **`rejected`**
- 用 `xattr -cr` 清理后 → 根节点 quarantine 被成功移除

这条实验直接证明：**分水岭是隔离标记，而不是 ad-hoc 签名本身。**

### 3.7 本机没有可用的签名证书

- `security find-identity -v -p codesigning` → `0 valid identities found`
- `CSC_LINK`、`CSC_IDENTITY_AUTO_DISCOVERY` 均未设置

所以产物只能 ad-hoc，electron-builder 自身的签名步骤必然被跳过。

### 3.8 隔离属性在"别人机器安装"路径上的传播（关键实测）

这一节直接回答"为什么别人拿去装也没被拦"。实测口径：把本工程 DMG 复制一份并人为写入 `com.apple.quarantine`，再模拟真实分发路径。

| 环节 | 实测结果 |
| --- | --- |
| DMG 文件本身 | 写入 `com.apple.quarantine` 成功（模拟浏览器下载） |
| 用 `open`（等价 Finder 双击）挂载后 | 挂载点带上 `quarantine` **挂载选项**（`mount` 可见 `... read-only, ... ,quarantine`），属于卷级标记 |
| 卷内 `AgentForge Desktop.app` 的文件 xattr | **只有 `com.apple.provenance`，没有 `com.apple.quarantine`** |
| 用 Finder 拖拽 / `ditto` 复制出 App | **目标 App 仍无 `com.apple.quarantine`** |
| 用 `cp -R` 复制出 App | 目标 App **带** `com.apple.quarantine: 0281;00000000;;` |

结论：**卷级 quarantine 只挂在挂载点上，不会自动下沉成 App 的文件属性；而"从 DMG 拖到应用程序"这个动作走的是 Finder/LaunchServices（等价 `ditto`），落地后不带隔离属性。** 只有用会保留/物化属性的工具（如 `cp -R`）复制，才会把 quarantine 写到 App 上。

补充一条 macOS 14.6 上的行为日志（用带 quarantine 的 App 副本实际启动时捕获）：

```
CoreServicesUIAgent: -[GKQuarantineResolver malwareCheckEnded:result:forURL:]: XProtect defer executable analysis: true
CoreServicesUIAgent: -[GKQuarantineResolver malwareChecksFinished]_block_invoke: XProtect suppress first launch warning: true
syspolicyd: Prompt shown (6, 0), waiting for response: ... (id: cloud.changlu.agentforge.desktop)
```

即：**即使真的带上 quarantine，只要 XProtect 未判定恶意，系统也会抑制"首次启动警告"**，最坏情况是弹一个需要用户点"打开"的确认框（不是"已损坏"这种硬拒绝）。这也解释了为什么不同人反馈不一致 —— 与机器、macOS 版本、以及 quarantine 有没有真正落到 App 上有关。

---

## 4. macOS 的两个独立关卡（问题本质）

社区常把两种情况混为一谈，实际是两套互不相关的机制：

| 关卡 | 触发条件 | 校验内容 | 不通过的表现 |
| --- | --- | --- | --- |
| **① 代码签名完整性**（内核，Apple Silicon 强制） | 每次执行 Mach-O | 有无签名（至少 ad-hoc）+ bundle seal 与内容是否一致 | "已损坏，无法打开，你应该将它移到废纸篓"、进程被直接杀掉 |
| **② Gatekeeper / spctl**（用户态） | 文件或其挂载卷带 `com.apple.quarantine` | Developer ID 证书 + 公证（notarization）票据 | "无法验证开发者"、"Apple 无法检查是否包含恶意软件"；macOS 14 起 XProtect 未报警时多为"确认打开"弹窗而非硬拒绝 |

四种组合的实际表现：

| 签名状态 | 有无 quarantine | 结果 |
| --- | --- | --- |
| 无签名 / seal 破裂 | 任意 | **必然打不开**（关卡①，内核拒绝） |
| ad-hoc、seal 自洽 | 无（本机构建） | **正常打开，无任何提示** ← 当前工程所处状态 |
| ad-hoc、seal 自洽 | 有（但经 Finder 从 DMG 拖拽安装） | **多数情况正常打开**：卷级 quarantine 不会下沉到 App，`spctl` 离线判定虽为 rejected，但 XProtect 未报警时只弹"确认打开"或直接放行 ← "别人也能装"的真正原因 |
| ad-hoc、seal 自洽 | 有（且属性确实落在 App 上，如 `cp -R`/某些解压工具） | 可能被拦：Mac 上多表现为需手动"仍要打开"，并非必然"已损坏" |
| Developer ID + hardened runtime + 公证 | 有 | 正常打开（关卡②通过，首次可能有网络校验） |

electron-builder 在无证书时的行为也印证了这一点：`identity == null` 且未开 `forceCodeSigning` 时，它只记录 `skipped macOS code signing` 并返回 `false`（`macPackager.js:188-199`），**不会失败、也不会补 ad-hoc**。所以"能不能跑"完全取决于 afterPack 这个钩子。

---

## 5. 为什么"之前会被拦截"

git 历史显示，`afterPack` 钩子首次出现在 `3ac8b5d`（`feat_1.0.x_4`，当时路径为 `scripts/after-pack-mac.mjs`），在 `0d2cb39`（`feat_1.0.x_5`）随脚本分层重组迁移到 `scripts/package/after-pack-mac.mjs`，内容自引入起未变。**在此之前，打包流程里没有任何签名或属性清理步骤。**

那个阶段的失败链条：

1. `security find-identity` 为 0 → electron-builder 走 `identity == null` 分支，跳过签名。
2. 于是 `.app` 被 `files` + `extraResources` 改动之后，**没有任何人重建 seal**，Electron 原始 seal 失效。
3. 关卡① 直接拒绝 → 表现为"已损坏，无法打开"，用户感知为"被安全审核拦了"。
4. 若这份 DMG 通过浏览器 / 微信 / AirDrop / 网盘传到目标机器，会写入 `com.apple.quarantine`，关卡② 再拦一次："无法验证开发者"。

现在的状态之所以变了：

- `xattr -cr` 把整包（含任何残留 quarantine）洗掉；
- `codesign --force --deep --sign -` 以 ad-hoc 重建与内容一致的 seal → 关卡① 通过；
- 产物从 `release/` 目录**直接拖进"应用程序"**，没有经过任何会写 quarantine 的通道（浏览器下载、IM 传输、AirDrop、邮件附件）→ 关卡② 不触发。

两条同时成立，宏观表现就是"安装时不需要安全审核了"。

> 另一个容易误判的点：`4585798`（"前端打包配置统一"）只改了图标与产品名注入，与签名无关；版本号 `0.1.0` 同样与签名无关。

---

## 6. 这个"免审核"的适用边界（重要）

1. **只对"本机构建、本机安装"成立。** 产物一旦经过网络/IM/AirDrop 流转到别人机器，ad-hoc 立刻被判 `rejected` —— §3.6 已实测。

> 修订（2026-11-27 补测）：上面这条需要收窄。`spctl` 的离线判定确实是 `rejected`，但**这不等于用户会被拦**：
> - quarantine 只挂在 DMG 挂载卷上（`mount` 显示卷级 `quarantine` 选项），拖到"应用程序"后 App 本身不带该属性（§3.8 实测）；
> - macOS 14.6 日志显示 XProtect 未报警时会 `suppress first launch warning`，最多弹一次"确认打开"。
> 所以"别人也能装"是**当前 macOS 真实行为**，不是同事运气好。真正的风险是**不可控**：机器/系统版本/解压工具/企业 MDM 策略不同，都会让结果从"无提示"变成"弹确认"甚至"已损坏"。

2. **ad-hoc 不是"可信"，只是"能跑"。** 无法上架、无法通过企业分发合规检查；在较老系统或属性确实落到 App 上的场景，用户第一次打开仍可能被要求手动放行。
3. **`--deep` 已被 Apple 标记为不推荐。** 当前能通过是因为整包 seal 自洽且内嵌 JRE 保留了原厂签名；一旦 `runtime/jre` 或 `runtime/tools` 里的二进制被替换/增删而重签时机不对，就会出现"签名自洽但嵌套代码校验失败"，或首启被杀。
4. **无 hardened runtime（缺 `0x10000` 标志）、无 entitlements、无公证票据。** 即便将来拿到 Developer ID 证书，也不能只靠现在的钩子完成可分发签名；届时必须移除 ad-hoc 重签，否则会覆盖正式签名与公证结果。
5. **钩子失败被静默吞掉。** `after-pack-mac.mjs` 两处都是 `try/catch` + `console.warn`（`xattr` 还用 `stdio: 'ignore'`）；`verify-mac-package.mjs:21-22` 对 `codesign --verify` 失败也只 `warn` 并注释为 "local build"。所以"打包成功"不代表"签名成功"，问题会被推迟到用户双击那一刻才暴露。
6. **`xattr -cr` 对内嵌 JRE 的只读文件会报 `Permission denied`（Errno 13，例如 `jre/LICENSE`、`jre/lib/cmm/*.pf`）。** 这些文件本无属性，当前无害；但报错被 `stdio: 'ignore'` 屏蔽，会掩盖真实的属性清理失败。
7. **产物与 `extraResources` 声明不一致。** `package.json` 声明了 `runtime/tools → runtime/tools`，但本次产物 `Contents/Resources/runtime/` 下**没有 `tools` 目录**（`prepare-rg.mjs` 不在这条 package 链路中被调用）。首次启动复制内置 ripgrep 的逻辑会退到 `electron/enhancements.mjs` 的在线下载分支（该分支会主动清理下载文件的 quarantine，见 `enhancements.mjs:453-455`），行为与"内置 rg"不同，需确认是否为预期。
8. **`package:current` / `package:mac` / `package:mac-*` 最终都汇入 `package-desktop.mjs`，共用同一套 afterPack 行为。** 所以这个"免审核"现象不是 `--arch arm64` 带来的，换 Intel 目标或 `package:current` 结果一致；macOS 与 Windows 的差异只在于 Windows 没有 Gatekeeper 这一层。

---

## 7. 结论

**为什么现在不弹审核：** 本机产出的 DMG/App 不带 `com.apple.quarantine`，Gatekeeper 从未介入；同时 afterPack 用 ad-hoc 重签补齐了 Apple Silicon 强制要求的签名完整性，于是既不被拦、也能启动。

**为什么别人拿去装也没被拦：** 隔离属性只落在 DMG 挂载卷（卷级 `quarantine` 选项），从 DMG 拖到"应用程序"这一步由 Finder/LaunchServices 执行，落地 App 不带该属性；即便偶发带上，macOS 14 的 XProtect 在未判定恶意时会抑制首次启动警告，只可能弹一次"确认打开"。所以拦截与否不取决于 ad-hoc 签名是否合规，而取决于隔离属性有没有真正落到 App 上。

**为什么以前会被拦：** 引入 afterPack 之前，打包过程既没有清属性也没有重签，`extraResources` 破坏了 Electron 原始 seal，关卡① 直接拒绝执行；若产物经过下载分发，关卡② 再补一刀。

**若要长期稳定，需按目标区分策略：**

| 目标 | 需要做的事 |
| --- | --- |
| 仅本机自用 / 内部同机验证 | 保持现状；建议把 afterPack 的失败从静默 `warn` 升级为可观测日志（并提供硬失败开关），让 verify 脚本区分"ad-hoc 正常"与"seal 破裂" |
| 分发给其他机器 | 必须具备 Developer ID Application 证书 + hardened runtime + entitlements + 公证（notarize）+ 票据装订（staple）；此时**必须移除** afterPack 里的 ad-hoc 重签 |
| 流程归一化 | 把"签名策略"做成显式配置项（ad-hoc / developer-id / 关闭），而不是藏在 afterPack 的 `catch` 里；所有 `package:*` 入口读同一份策略 |