<template>
  <div class="interview-container">
    <div class="header">
      <div class="header-left">
        <div class="back-button" @click="goBack">返回</div>
        <button class="upload-button" :disabled="uploading" @click="triggerUpload">
          {{ uploading ? '上传中...' : '📄 上传简历' }}
        </button>
      </div>
      <h1 class="title">AI智能面试官</h1>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>

    <!-- 隐藏的原生文件选择框，点击"上传简历"按钮时触发 -->
    <input
      ref="fileInput"
      type="file"
      accept=".pdf"
      style="display: none"
      @change="onFileChange"
    />

    <div class="content-wrapper">
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="interview"
          @send-message="sendMessage"
        />
      </div>
    </div>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithInterviewApp, startInterviewApp, uploadResume } from '../api'

// 设置页面标题和元数据
useHead({
  title: 'AI智能面试官 - 李嘉图AI超级智能体应用平台',
  meta: [
    {
      name: 'description',
      content: 'AI智能面试官是李嘉图AI超级智能体应用平台的资深程序员面试官，基于你的简历针对性地深挖项目经历，进行多轮模拟面试'
    },
    {
      name: 'keywords',
      content: 'AI智能面试官,模拟面试,程序员面试,Java后端,深挖项目,AI聊天,李嘉图,AI智能体'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
let eventSource = null
const fileInput = ref(null)
const uploading = ref(false)

// 生成随机会话ID
const generateChatId = () => {
  return 'interview_' + Math.random().toString(36).substring(2, 10)
}

// 添加消息到列表（AI 消息自带一个空思考链 steps，用于承载 thought 步骤）
const addMessage = (content, isUser, steps = []) => {
  messages.value.push({
    content,
    isUser,
    steps,
    time: new Date().getTime()
  })
}

// 把正文文本追加到指定的 AI 消息
const appendTextToMessage = (index, text) => {
  if (index < messages.value.length && text) {
    messages.value[index].content += text
  }
}

// 把一个思考步骤追加到指定 AI 消息的思考链
const appendThinkingStep = (index, type, content) => {
  if (index < messages.value.length) {
    messages.value[index].steps.push({ type, content, time: Date.now() })
  }
}

// 结束本轮流式：收起连接、标记状态
const finishStream = () => {
  connectionStatus.value = 'disconnected'
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

// 分发后端推来的结构化事件
const handleStreamEvent = (event, index) => {
  const { type, content } = event

  if (type === 'thought' || type === 'tool') {
    // 思考步骤 / 工具调用：进思考链，不进聊天气泡
    appendThinkingStep(index, type, content)
    return
  }

  if (type === 'text') {
    // 正式回答文本：进聊天气泡，保持打字机效果
    appendTextToMessage(index, content)
    return
  }

  if (type === 'done') {
    finishStream()
  }
}

// 打开一条 SSE 流：创建一个空的 AI 气泡（自带空思考链）并连接后端。
// autoStart = true 时走「自动开场」通道，消息体由 api 层统一提供，不显示用户气泡。
const openStream = (message, autoStart = false) => {
  // 连接SSE
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }

  // 创建一个空的AI回复消息（自带空思考链）
  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'

  // onMessage：纯文本兜底（新协议下一般不会走到）
  const onMessage = (data) => {
    if (data === '[DONE]') {
      finishStream()
      return
    }
    appendTextToMessage(aiMessageIndex, data)
  }

  // onError
  const onError = (error) => {
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    finishStream()
  }

  // onEvent：结构化事件（thought / text / tool / done）
  const onEvent = (event) => {
    handleStreamEvent(event, aiMessageIndex)
  }

  eventSource = autoStart
    ? startInterviewApp(chatId.value, onMessage, onError, onEvent)
    : chatWithInterviewApp(message, chatId.value, onMessage, onError, onEvent)
}

// 发送消息（用户主动发问：先落用户气泡，再开流）
const sendMessage = (message) => {
  addMessage(message, true)
  openStream(message)
}

// 简历上传成功后自动开场：不落用户气泡，用户看到的只有 AI 紧接着给出的开场白
const startAutoInterview = () => {
  openStream('', true)
}

// 触发隐藏的文件选择框
const triggerUpload = () => {
  if (fileInput.value) {
    fileInput.value.click()
  }
}

// 选择文件后触发上传
const onFileChange = async (event) => {
  const file = event.target.files && event.target.files[0]
  if (!file) return

  // 前端先做一次轻量校验，尽早拦截明显不合规的文件
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    alert('只支持 PDF 格式的简历文件')
    resetFileInput()
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    alert('简历文件超过 5MB，请压缩后重试')
    resetFileInput()
    return
  }

  uploading.value = true
  try {
    const res = await uploadResume(chatId.value, file)
    if (res.data && res.data.success) {
      // 上传成功不弹提示框：AI 紧接着给出的开场白本身就是最好的成功反馈。
      // 若此处用 alert，模态框会卡住页面，开场白得等用户点「确定」才会开始，正好破坏要做的效果。
      startAutoInterview()
    } else {
      alert((res.data && res.data.message) || '上传失败，请重试')
    }
  } catch (error) {
    const msg = error.response && error.response.data && error.response.data.message
      ? error.response.data.message
      : (error.message || '上传失败，请重试')
    alert('上传失败：' + msg)
  } finally {
    uploading.value = false
    resetFileInput()
  }
}

// 清空文件选择框，让同一个文件可以再次选择
const resetFileInput = () => {
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  // 生成聊天ID
  chatId.value = generateChatId()

  // 添加欢迎消息
  addMessage('你好，我是你的资深 AI 面试官。你可以直接做一段自我介绍，也可以把简历的文字内容粘贴给我，我会针对你的项目经历进行深挖提问。准备好了吗？', false)
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.interview-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f5f7fa;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: #2563eb;
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.upload-button {
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  padding: 6px 12px;
  background-color: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 6px;
  color: white;
  transition: background-color 0.2s;
}

.upload-button:hover {
  background-color: rgba(255, 255, 255, 0.3);
}

.upload-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
}

.chat-id {
  font-size: 14px;
  opacity: 0.8;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  /* 设置最小高度确保内容显示正常 */
  min-height: calc(100vh - 56px - 180px); /* 100vh减去头部高度和页脚高度 */
  margin-bottom: 16px; /* 为页脚留出空间 */
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }

  .title {
    font-size: 18px;
  }

  .chat-id {
    font-size: 12px;
  }

  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px); /* 调整计算值 */
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }

  .back-button {
    font-size: 14px;
  }

  .upload-button {
    font-size: 12px;
    padding: 4px 8px;
  }

  .title {
    font-size: 16px;
  }

  .chat-id {
    display: none;
  }

  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px); /* 再次调整计算值 */
    margin-bottom: 8px;
  }
}
</style>