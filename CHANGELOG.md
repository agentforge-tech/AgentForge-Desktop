# Changelog

所有对本项目的重要更改都将记录在此文件中。

本日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并且项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

发布记录同步：

- GitHub Releases：https://github.com/agentforge-tech/AgentForge-Desktop/releases

# release_1.0.x

## v0.1.0 - 2026.11.01-2026.11.23

状态：开发中 🚧

### Added

- [feat_1.0.x_10] 桌面端本地资源输入与模态识别：输入框支持图片、文件、文件夹内联资源 Chip 与类型标记，按光标位置原位插入，新增「+ → 上传文件 → 本地文件/本地文件夹」二级菜单，支持 Electron 原生选择、拖拽与粘贴；后端新增资源解析、prompt 标记替换、AgentAttachment 转换、DesktopLocalImageLoader、read_resource 工具与 ReadArtifactMiddleware 对接，模型配置支持显式视觉能力；新增 ai_agent_session_message.req 增量 SQL 与 req.rawMessage 历史回放，用户消息气泡按输入框资源位置原样渲染；补充本地资源输入与模态识别设计文档及资源解析、载荷路由单测
- [4585798] 桌面端打包配置统一与 macOS 窗口样式迭代：新增 scripts/conf/package.json 与 package-config.mjs 统一管理应用名称和图标，校验 PNG 格式与方形 512px 以上尺寸，打包脚本读取配置并向 electron-builder 注入 productName 与平台图标；macOS 启用 hiddenInset 标题栏，preload 暴露 platform，App 增加可拖拽标题栏与侧栏/聊天头部拖拽区；输入框模型选择重构为自定义展示区，显示模型名称与下拉箭头并新增 OpenAI 标识，优化模型字样与侧栏间距样式；后端 LocalRuntime 命令超时设为 5 分钟避免长时间阻塞，模型连通性测试补充产品 User-Agent 标识；新增前端打包配置设计文档并整理侧栏折叠与动态高度文档目录
- [e27d593] 聊天任务可断线重连续流与 Skill 管理迭代：聊天接口拆分为 submit/stream/stop/resume 任务化链路，新增基于 SQLite 的消息事件存储、增量序号与断线重连补流，前端统一 GET SSE 订阅并在会话切换、任务恢复时按 lastEventSeq 去重续流；新增全局 Skill 加载器、列表/导入/根目录接口及桌面端 Skill 工具，前端新增技能管理页支持文件夹与 ZIP 导入、拖拽上传和右键打开目录；设置抽屉模型配置新增复制入口，一键预填为新增表单并在名称追加（复制）标识；品牌图与 DeepSeek 模型标识改为 ESM 资源导入，新建任务与技能管理图标内联 SVG，修复打包后图标与模型 logo 不显示问题；构建脚本改用隔离 Maven settings 并跳过 SNAPSHOT 在线校验，降低私服不可达导致的打包失败；补充 Skill 实现方案、多栏折叠与动态高度等设计文档
- [4bd8f9f] 工具调用可读性优化：内置提示词要求每轮工具调用简述目的与用途并约束敏感信息；优雅模式为 Bash/Shell 等命令工具新增终端 SVG 图标，有 thought 时隐藏技术工具名并兼容双字段；新增本地工作台图标与工具名映射规范文档
- [3e9f372] 工作台工作空间与任务交互迭代：新增工作空间选择、创建导入、树形会话列表及打开重命名删除菜单；重构空间、置顶与普通任务独立折叠滚动和今日昨日分组，完善会话置顶与目录操作；优化输入框布局、模型切换及 DeepSeek 标识、模型连通性测试和追加消息展示，并补充工作空间设计方案
- [dabcee4] 工作空间后端能力迭代：新增工作空间表、创建导入重命名删除接口与会话绑定字段；首轮对话按默认时间目录或所选空间完成真实目录创建和运行时绑定；删除空间安全解绑会话并保留本地文件，补齐 Mapper、迁移脚本及绑定服务测试
- [0a1994a] 桌面端交互治理配套更新：构建脚本完善 Maven 参数与内置服务打包适配；README 补充追加指令、HITL、任务停止与多轮响应分层能力说明；新增对话交互、HITL 与停止机制实现文档，并同步桌面端设计索引和 embedded 本地调试流程
- [7093ecb] 工作台多轮交互迭代：新增执行中追加指令队列、AskUser 与权限审批 HITL 卡片及历史轨迹展示；同步停止与恢复状态交互，优化新建会话工作空间重置、底部提示居中和取消态展示；按 Agent Loop 将中间响应分层归档为过程节点，统一实时流与历史回放语义，并适配调试、发布两种渲染模式
- [ca853b6] 桌面端对话运行治理迭代：新增执行中用户消息追加的 SQLite 存储、状态流转、接口与 Middleware 消费链路；实现 AskUser/权限审批 HITL 快照持久化、恢复运行注册与 Shell 命令权限规则；完善会话 executionId 级停止、取消超时收口和状态迁移，补齐数据库增量脚本及停止映射、权限治理、中断编解码与存储测试
- [c4586b7] README 双语化与仓库元信息优化：默认 README 改为英文并重构为定位、特性、架构图、技术栈、仓库结构、环境要求、快速开始、配置数据路径、打包、数据库迁移与文档索引；新增 README.zh-CN.md 作为同步中文版并互相链接；README 补充 workspace 选择、HarnessAgent 运行时、内置提示词、日志与双平台产物校验等当前能力
- [0d2cb39] 桌面端 Harness 运行时与工作区迭代：后端运行时由 ReActAgent 切换为 HarnessAgent+LocalRuntime，聊天接口新增 projectPath 工作区选择与 executionId 级取消，内置系统提示词由 Desktop 规则、本机环境与工作区约束自动组装；配置拆分为 conf/config.json 与 conf/models.json 并自动迁移旧 config.json，协议处理器迁移至 service-protocal 依赖、移除本地 protocol 包，补齐 logback 统一日志与 SQLite 迁移台账说明；前端工作台新增工作空间选择、Agent 配置页与设置抽屉合并编辑、内置提示词只读展示、Markdown 代码块一键复制与模型密钥回显；构建脚本按 build/dev/package/verify/lib 分层重组并新增 doctor 环境体检；新增 WorkBuddy 文件写入实时流、日志统一管控、安装后初始化与 SQLite 增量迁移、快捷一键打包三端适配、desktop-dev embedded 等文档并同步 README
- [3ac8b5d] Agent 运行期交互能力迭代：新增执行中追加用户消息的 Middleware、存储与接口链路，补齐追加事件状态流转、会话持久化及调试/发布页展示；增加 AI 调用重试流式事件与前端提示，完善 ReAct 流式测试、MySQL/SQLite 增量脚本和运行配置分包；补充 Memory、Middleware、Tool SDK 文档及上下文压缩设计方案并同步 version-1.0 文档快照、RoadMap 与 CHANGELOG

### Changed

- [docs_1.0.x_11] README 中英文同步当前工作台能力：补充任务化流式与断线续流、本地资源原位输入与模态识别、read_resource、技能管理、显式视觉能力与工作空间底栏规则，并加入本地资源设计文档与 CHANGELOG 入口
- [feat_1.0.x_10] 输入区与会话列表统一为 760px 居中布局；工作空间底栏在新建会话时位于输入框下方、已有会话时位于输入框上方，两种状态保持相同高度与外角；非工作空间会话隐藏工作空间选择器与灰色底栏；`+` 按钮改为无边框透明样式，资源始终插入当前光标位置
- [4585798] 统一桌面端打包配置来源并优化 macOS 窗口与输入框模型选择交互
- [e27d593] 聊天流式消费由单次连接升级为任务化 submit/stream 与断线重连续流
- [3e9f372] 工作台会话列表按工作空间、置顶任务与日期分组重构
- [0d2cb39] 后端运行时收敛为 HarnessAgent + LocalRuntime，配置与构建脚本按运行职责重新分层

### Fixed

- [feat_1.0.x_10] 修复文件夹资源选择后缺少可用上下文的问题：选择文件夹时附带目录概览，Agent 可继续通过 read_resource 深入读取；修复非工作空间会话仍展示工作空间入口的问题；资源插入改为严格跟随输入框光标，失效时退化为末尾插入
- [e27d593] 修复打包后图标与模型 logo 不显示问题
- [4585798] 修复 macOS 窗口样式与模型标识展示问题
