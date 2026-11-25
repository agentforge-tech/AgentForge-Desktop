# AgentForge-Desktop 系统配置读取与扩展说明

> 对应仓库：`AgentForge-Desktop`
> 相关模块：`electron/main.mjs`、`electron/preload.cjs`、`src/components/ConfigDrawer.vue`、`agentforge-desktop-server` 的 `DesktopProperties`
> 适用范围：桌面端本地单实例运行模式

---

## 1. 能力范围

系统配置用于承载桌面端的全局运行开关，与 Agent 配置（`conf/config.json`）、模型配置（`conf/models.json`）分开存放，统一落在同一配置目录下的 `settings.json`：

```text
<rootDir>/conf/settings.json
```

当前包含一个配置项：

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `interactionModeSupport` | boolean | `false` | 是否允许在工作台切换交互模式，对应设置界面“交互模式支持切换” |

设置界面入口：“设置”弹窗 → 左侧“系统配置” → 复选项 + 底部“保存系统配置”按钮。

---

## 2. 总体结构

```text
ConfigDrawer.vue（系统配置栏）
  ├─ 打开设置时：readSystemSettings()
  └─ 点击保存时：saveSystemSettings(systemForm)
        │  contextBridge（window.electronAPI）
        ▼
preload.cjs
  ├─ local-agent:read-system-settings
  └─ local-agent:save-system-settings
        │  ipcRenderer.invoke
        ▼
main.mjs（主进程）
  ├─ systemSettingsPath()      计算配置文件绝对路径
  ├─ readSystemSettingsFile()  读取 + 白名单规范化 + 默认值兜底
  └─ save-system-settings     白名单校验 + 建目录 + 写文件
        │
        ▼
<rootDir>/conf/settings.json
```

渲染进程不持有 Node 文件系统权限，全部文件访问都收敛在主进程，避免任意路径读写。

---

## 3. 配置数据结构

最小可用配置：

```json
{
  "interactionModeSupport": false
}
```

约定：

- 字段名使用 camelCase，语义稳定，不随 UI 文案变化。
- 每个字段必须有明确默认值；缺文件、缺字段、JSON 损坏时都要回退到默认值。
- 保存时按白名单写入，不能把 IPC 传入的任意对象原样落盘。
- 布尔字段用严格比较 `=== true`，避免 `"false"` 字符串被当成开启。

推荐规范化写法：

```js
const DEFAULT_SETTINGS = {
  interactionModeSupport: false
}

function normalizeSettings(value) {
  const input = value && typeof value === 'object' ? value : {}
  return {
    ...DEFAULT_SETTINGS,
    interactionModeSupport: input.interactionModeSupport === true
  }
}
```

读取与保存都只返回规范化后的对象，保证 UI 状态和磁盘内容一致。

---

## 4. 读取流程

1. 打开设置抽屉触发 `ConfigDrawer.load()`。
2. 先调用 `readSystemSettings()`，读取成功后写入 `systemForm`。
3. 读取失败或环境不支持（浏览器模式）时保持默认 `false`，不阻塞其他配置加载。
4. 主进程读取时：

```js
function readSystemSettingsFile() {
  try {
    const raw = fs.readFileSync(systemSettingsPath(), 'utf8')
    const parsed = JSON.parse(raw)
    return { interactionModeSupport: parsed?.interactionModeSupport === true }
  } catch (_error) {
    return { interactionModeSupport: false }
  }
}
```

注意：读取失败是“静默回退默认值”，而不是抛出异常。这样配置文件首次不存在或用户手工改坏 JSON 时，设置界面仍可正常打开。

---

## 5. 保存流程

1. 用户点击“保存系统配置”。
2. 渲染层调用 `saveSystemSettings(this.systemForm)`。
3. 主进程执行：

```js
const target = systemSettingsPath()
const normalized = {
  interactionModeSupport: settings?.interactionModeSupport === true
}
fs.mkdirSync(path.dirname(target), { recursive: true })
fs.writeFileSync(target, `${JSON.stringify(normalized, null, 2)}\n`, 'utf8')
return normalized
```

要点：

- `mkdir -p` 语义：首次保存自动创建 `conf` 目录。
- 使用两空格缩进并保留结尾换行，便于版本管理和人工排查。
- 返回实际写入的规范化对象，UI 用它更新本地状态，避免“界面显示已开启但磁盘写入的是 false”这类偏差。
- 保存失败时向渲染层抛错，UI 提示“系统配置保存失败”，并保证 `savingSystem` 复位。

---

## 6. 路径约定（重要）

后端 `DesktopProperties` 已经确定了配置目录规则：

```java
public Path configPath() {
    return rootPath().resolve("conf").resolve("config.json");
}
```

即根目录默认为 `${user.home}/.agentforge-desktop`，可用 `AGENTFORGE_DESKTOP_ROOT_DIR` 覆盖；Agent 配置在 `conf/config.json`，模型配置在 `conf/models.json`。

因此系统配置应严格等于：

```text
<rootPath>/conf/settings.json
```

当前 `main.mjs` 的 `systemSettingsPath()` 默认取了 `process.cwd()/agentscope-desktop`，与后端根目录不一致，会出现：

- 设置界面保存到仓库目录，后端读不到。
- 打包后 `process.cwd()` 不稳定，路径随启动方式变化。
- 用户自定义 `AGENTFORGE_DESKTOP_ROOT_DIR` 时完全不生效。

建议改造为：启动阶段确定一次 `rootDir`（与启动 Java 子进程时传入 `--agentforge.desktop.root-dir` 的值完全一致），存入主进程共享状态，配置路径统一从该值计算：

```js
const rootDir = process.env.AGENTFORGE_DESKTOP_ROOT_DIR
  ? path.resolve(process.env.AGENTFORGE_DESKTOP_ROOT_DIR)
  : path.join(app.getPath('home'), '.agentforge-desktop')

function systemSettingsPath() {
  return path.join(rootDir, 'conf', 'settings.json')
}
```

如果产品确实希望开发时把配置放在仓库内 `./agentscope-desktop`，应仅在 `!app.isPackaged` 时启用，并在文档中显式标注，避免与后端根目录冲突。

---

## 7. 当前设置功能设计效果

本节描述系统配置在界面上的实际呈现效果与交互行为，与实现代码保持同步。

### 7.1 设置入口

设置入口位于工作台左侧栏底部状态条右侧的设置按钮（齿轮图标），点击后打开“设置”弹窗。

```text
工作台左侧栏
  └─ 底部状态条：[连接状态圆点] 本地服务已连接        [⚙ 设置]
```

### 7.2 设置弹窗结构

弹窗为左右两栏布局：

```text
┌──────────────────────────────────────────────────────────┐
│  设置                                                  × │
├───────────────┬──────────────────────────────────────────┤
│ 配置          │  系统配置                                 │
│               │  管理桌面端系统级运行参数                 │
│ ◆ 模型配置    │                                          │
│ ◆ 默认Agent   │  ┌────────────────────────────────────┐  │
│ ◆ 系统配置    │  │ 交互模式支持切换            [ ✓ ]  │  │
│               │  │ 允许在工作台中切换交互模式         │  │
│               │  └────────────────────────────────────┘  │
│               │                     [保存系统配置]       │
└───────────────┴──────────────────────────────────────────┘
```

左侧三个栏目的图标语义：

| 栏目 | 图标 | 含义 |
| --- | --- | --- |
| 模型配置 | 芯片 | 模型与算力配置 |
| 默认 Agent 配置 | 机器人 | Agent 助手身份与参数 |
| 系统配置 | 调节滑杆 | 系统级参数开关 |

### 7.3 系统配置栏目

栏目包含一个配置行：

- 左侧：标题“交互模式支持切换”与说明“允许在工作台中切换交互模式”。
- 右侧：复选框控件，默认未勾选。
- 底部：右对齐的“保存系统配置”按钮，保存中显示 loading，成功后提示“系统配置已保存”。

切换栏目时表单状态保留；重新打开弹窗会从 `conf/settings.json` 重新读取当前值。

### 7.4 工作台右上角联动效果

配置值直接决定工作台头部工具栏是否渲染交互模式下拉框：

**未开启（默认）**

```text
┌───────────────────────────────────────────────┐
│ 新对话                                        │
└───────────────────────────────────────────────┘
```

- 不渲染模式下拉框，工具栏为空。
- 消息统一按发布模式（优雅交互）渲染。

**已开启**

```text
┌───────────────────────────────────────────────┐
│ 新对话        [ 发布模式 · 优雅交互  ▾ ]      │
└───────────────────────────────────────────────┘
```

- 渲染模式下拉框，候选为“调试模式 · 事件时间线”与“发布模式 · 优雅交互”。
- 切换后立即改变消息渲染方式，并写入本地偏好。

### 7.5 状态流转

```text
应用启动
  └─ 读取 conf/settings.json
       ├─ interactionModeSupport = false → 隐藏下拉框 + 发布模式
       └─ interactionModeSupport = true  → 显示下拉框 + 使用本地模式偏好

保存系统配置
  └─ 成功 → 抛出 system-saved → 工作台即时应用
       ├─ 关闭：移除本地模式偏好，回到发布模式，下拉框消失
       └─ 开启：下拉框出现，可切换调试 / 发布模式
```

### 7.6 异常与边界表现

| 场景 | 表现 |
| --- | --- |
| 配置文件不存在 | 按默认关闭处理，界面不报错 |
| JSON 损坏 | 按默认关闭处理，界面不报错 |
| 字段类型错误（如字符串 `"true"`） | 按 `false` 处理，避免误开启 |
| 浏览器直开（无 `window.electronAPI`） | 按未开启处理，保存时提示“当前环境不支持保存系统配置” |
| 本地残留过调试模式偏好 | 配置关闭时清除偏好并回到发布模式 |

---

## 8. 配置项能力说明

后续每个系统配置项在此独立成小节，新增配置时追加即可，保持“一项一小节”的结构。

### 8.1 interactionModeSupport · 交互模式支持切换

| 项目 | 内容 |
| --- | --- |
| 配置键 | `interactionModeSupport` |
| 类型 | boolean |
| 默认值 | `false`（不开启） |
| 存储位置 | `<rootDir>/conf/settings.json` |
| 作用范围 | 前端工作台右上角的“交互模式”切换控件 |
| 生效时机 | 保存后立即生效，无需重启 |

**控制的界面能力**

- 关闭时：工作台右上角不显示交互模式下拉框，渲染固定使用默认模式 `elegant`（发布模式 · 优雅交互）。
- 开启时：右上角显示交互模式下拉框，可在“调试模式 · 事件时间线”和“发布模式 · 优雅交互”之间切换。

**读取链路**

```text
ChatWorkbench.vue created()
  └─ loadSystemSettings()
       └─ window.electronAPI.readSystemSettings()
            └─ IPC local-agent:read-system-settings
                 └─ readSystemSettingsFile() → conf/settings.json
```

读取结果写入组件状态 `interactionModeSupport`，再由计算属性 `modeSwitchEnabled` 决定下拉框是否渲染：

```js
modeSwitchEnabled() {
  return this.interactionModeSupport === true
}
```

**保存后即时联动**

设置弹窗保存系统配置成功后，`ConfigDrawer.vue` 会向父组件抛出 `system-saved` 事件：

```js
const saved = await window.electronAPI.saveSystemSettings(this.systemForm)
this.$emit('system-saved', saved || { ...this.systemForm })
```

`ChatWorkbench.vue` 监听该事件并更新状态，因此无需重新打开页面或重启应用：

```html
<ConfigDrawer ... @system-saved="onSystemSettingsSaved" />
```

```js
onSystemSettingsSaved(settings) {
  this.applySystemSettings(settings)
}
```

**回退策略**

配置关闭时主动把当前渲染模式重置为默认值 `DEFAULT_RENDER_MODE`（`elegant`，发布模式），并清除本地模式偏好，避免用户停留在仅开关开启时才可选的调试模式，也避免下次启动又恢复成调试模式：

```js
applySystemSettings(settings) {
  this.interactionModeSupport = settings?.interactionModeSupport === true
  if (!this.interactionModeSupport) {
    localStorage.removeItem(MODE_KEY)
    this.mode = DEFAULT_RENDER_MODE
  }
}
```

默认值定义在 `src/modes/index.js`：

```js
// 桌面端默认展示发布模式，调试模式仅在开启“交互模式支持切换”后手动选择。
export const DEFAULT_RENDER_MODE = 'elegant'
```

`getRenderMode()` 在遇到未知模式键时同样回落到该默认值，保证历史遗留的 `debug` 记录不会让界面停在调试视图。

**涉及代码**

- `src/pages/workbench/ChatWorkbench.vue`：读取配置、`modeSwitchEnabled` 计算属性、`loadSystemSettings` / `applySystemSettings` / `onSystemSettingsSaved` 方法，以及右上角下拉框的 `v-if` 控制。
- `src/components/ConfigDrawer.vue`：系统配置栏目、保存逻辑与 `system-saved` 事件。
- `electron/preload.cjs`：`readSystemSettings` / `saveSystemSettings` 桥接方法。
- `electron/main.mjs`：`desktopRootDir()`、`systemSettingsPath()` 与读写 IPC handler。

**验证要点**

1. 默认（配置为 `false`）：右上角看不到模式下拉框，消息以发布模式（优雅交互）渲染。
2. 打开“交互模式支持切换”并保存：下拉框立即出现，可切换两种模式。
3. 关闭开关并保存：下拉框立即隐藏，渲染回到默认模式。
4. 重启应用后：界面状态与 `conf/settings.json` 保持一致。
5. 配置文件损坏或缺失：按默认关闭处理，界面不报错。

---

## 9. 新增配置项的标准步骤

以新增 `exampleFeatureEnabled` 为例：

1. 主进程默认值：在 `DEFAULT_SETTINGS` 增加 `exampleFeatureEnabled: false`。
2. 读取规范化：在 `normalizeSettings` 中加入该字段的类型校验与默认回退。
3. 保存白名单：在保存逻辑中加入同名字段，忽略未知字段。
4. preload：一般无需新增方法，复用 `readSystemSettings` / `saveSystemSettings` 即可。
5. UI：在 `ConfigDrawer.vue` 的 `systemForm` 增加同名字段，并在“系统配置”栏增加对应控件——布尔值用 `el-checkbox`/`el-switch`，枚举用 `el-select`，数值用带 `min`/`max` 的 `el-input-number`。
6. 生效逻辑：如果配置需要影响运行行为，必须在真实功能代码中读取并应用；仅做 UI 勾选不算完成。
7. 文档：同步更新本文的数据结构、默认值和生效时机说明。

### 字段设计建议

| 场景 | 推荐类型 | 备注 |
| --- | --- | --- |
| 功能开关 | boolean | 默认关闭，严格比较 `=== true` |
| 枚举选择 | string | 需校验候选集合，未知值回退默认 |
| 阈值/数量 | number | 需限定最小最大值并处理 `NaN` |
| 复杂组合 | object | 建议单独拆文件，避免 `settings.json` 膨胀 |

---

## 10. 配置变更的生效方式

- 即时生效：UI 直接依赖该配置项时，保存成功后更新本地状态即可。
- 需重启：影响 Java 子进程启动参数或初始化流程的配置，保存后提示用户重启桌面端。
- 需刷新：影响会话上下文的配置，保存后应触发对应数据重新加载。

不建议在系统配置中存放密钥类信息（如 API Key），这类内容应留在模型配置中。

---

## 11. 测试清单

- 文件不存在：返回完整默认对象，界面显示为关闭，启动后为发布模式。
- 正常读取：勾选状态与磁盘一致。
- 缺字段：缺失字段回退默认值，其余字段不受影响。
- 类型错误（如 `"true"` 字符串）：回退为 `false`，不出现真值误判。
- 损坏 JSON：设置窗口仍能打开，显示默认值。
- 保存后可重新读取到相同结果。
- 未知字段不会写入磁盘。
- 自定义 `AGENTFORGE_DESKTOP_ROOT_DIR` 时，读写都落在同一 `conf/settings.json`。
- 浏览器模式（无 `window.electronAPI`）：提示“当前环境不支持保存系统配置”，不报未捕获异常。

---

## 12. 当前实现状态

- UI：设置弹窗已包含“系统配置”栏目、`interactionModeSupport` 复选框与“保存系统配置”按钮，默认关闭。
- 联动：该配置已接入工作台右上角的交互模式切换控件，保存后即时生效；关闭时隐藏控件并回到默认渲染模式。
- 默认模式：`DEFAULT_RENDER_MODE = 'elegant'`，即应用启动默认展示发布模式（优雅交互），不是调试模式。
- 链路：渲染层 → preload `contextBridge` → 主进程 IPC → 本地 JSON，渲染层不直接读写文件。
- 路径：`desktopRootDir()` 已作为唯一根目录来源，系统配置写入 `<rootDir>/conf/settings.json`，与后端 `conf/config.json`、`conf/models.json` 同目录；支持 `AGENTFORGE_DESKTOP_ROOT_DIR` 覆盖。
- 注意：修改源码后需重新构建前端产物（`npm run build`），桌面端才会加载新界面。
