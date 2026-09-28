import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: '首页 - 李嘉图AI超级智能体应用平台',
      description: '李嘉图AI超级智能体应用平台提供AI智能面试官和AI超级智能体服务，满足您的各种AI对话需求'
    }
  },
  {
    path: '/interview',
    name: 'Interview',
    component: () => import('../views/Interview.vue'),
    meta: {
      title: 'AI智能面试官 - 李嘉图AI超级智能体应用平台',
      description: 'AI智能面试官是李嘉图AI超级智能体应用平台的资深程序员面试官，基于你的简历针对性地深挖项目经历，进行多轮模拟面试'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/SuperAgent.vue'),
    meta: {
      title: 'AI超级智能体 - 李嘉图AI超级智能体应用平台',
      description: 'AI超级智能体是李嘉图AI超级智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router 