import Vue from 'vue'
import VueRouter from 'vue-router'

Vue.use(VueRouter)

const routes = [
  { path: '/', redirect: '/workbench' },
  {
    path: '/workbench',
    name: 'workbench',
    component: () => import('../pages/workbench/ChatWorkbench.vue'),
    meta: { title: '工作台' }
  },
  {
    path: '/models',
    name: 'models',
    component: () => import('../pages/models/ModelsPage.vue'),
    meta: { title: '模型配置' }
  },
  {
    path: '/agents',
    name: 'agents',
    component: () => import('../pages/agents/AgentsPage.vue'),
    meta: { title: 'Agent 配置' }
  },
  {
    path: '/agents/config',
    name: 'agent-config',
    component: () => import('../pages/agents/AgentConfigPage.vue'),
    meta: { title: '编辑 Agent' }
  },
  { path: '*', redirect: '/workbench' }
]

const router = new VueRouter({
  mode: 'history',
  routes
})

router.afterEach((to) => {
  document.title = to.meta && to.meta.title ? `${to.meta.title} · AgentForge Desktop` : 'AgentForge Desktop'
})

export default router
