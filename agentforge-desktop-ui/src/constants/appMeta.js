// 构建期由 vite.config.js 从 scripts/conf/package.json 注入。
export const APP_NAME = typeof __APP_NAME__ === 'undefined' ? 'AgentForge Desktop' : __APP_NAME__
export const APP_TAG = typeof __APP_TAG__ === 'undefined' ? '' : __APP_TAG__
