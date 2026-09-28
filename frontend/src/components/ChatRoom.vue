<template>
  <div class="chat-container">
    <!-- 聊天记录区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper">
        <!-- 思考链（仅 AI 消息且存在步骤时显示；默认折叠，进行中才自动展开） -->
        <div v-if="!msg.isUser && msg.steps && msg.steps.length > 0" class="thinking-panel">
          <div class="thinking-header" @click="toggleThinking(index)">
            <span class="thinking-icon">🧠</span>
            <span class="thinking-title">思考链</span>
            <span class="thinking-count">{{ msg.steps.length }} 步</span>
            <span class="thinking-chevron" :class="{ expanded: isThinkingExpanded(msg, index) }">▾</span>
          </div>
          <div v-show="isThinkingExpanded(msg, index)" class="thinking-body">
            <div v-for="(step, si) in msg.steps" :key="si" class="thinking-step">
              <span class="step-dot" :class="isStepRunning(si, msg, index) ? 'running' : 'done'"></span>
              <span class="step-text">{{ step.content }}</span>
            </div>
          </div>
        </div>

        <!-- AI消息 -->
        <div v-if="!msg.isUser"
             class="message ai-message"
             :class="[msg.type]">
          <div class="avatar ai-avatar">
            <AiAvatarFallback :type="aiType" />
          </div>
          <div class="message-bubble">
            <div class="message-content">
              {{ msg.content }}
              <span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="typing-indicator">▋</span>
            </div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
        </div>
        
        <!-- 用户消息 -->
        <div v-else class="message user-message" :class="[msg.type]">
          <div class="message-bubble">
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
          <div class="avatar user-avatar">
            <div class="avatar-placeholder">我</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input-container">
      <div class="chat-input">
        <textarea 
          v-model="inputMessage" 
          @keydown.enter.prevent="sendMessage"
          placeholder="请输入消息..." 
          class="input-box"
          :disabled="connectionStatus === 'connecting'"
        ></textarea>
        <button 
          @click="sendMessage" 
          class="send-button"
          :disabled="connectionStatus === 'connecting' || !inputMessage.trim()"
        >发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, watch, computed } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: 'disconnected'
  },
  aiType: {
    type: String,
    default: 'default'  // 'interview' 或 'super'
  }
})

const emit = defineEmits(['send-message'])

const inputMessage = ref('')
const messagesContainer = ref(null)

// 思考链手动展开/收起记录（key = 消息下标）
const manuallyToggled = reactive({})

// 某条消息的思考链是否展开：
// 默认规则「默认折叠，进行中才展开」——仅当正在连接、且是最后一条消息时自动展开；
// 用户点过标题后，以用户的手动选择为准
const isThinkingExpanded = (msg, index) => {
  if (Object.prototype.hasOwnProperty.call(manuallyToggled, index)) {
    return manuallyToggled[index]
  }
  return props.connectionStatus === 'connecting' && index === props.messages.length - 1
}

const toggleThinking = (index) => {
  manuallyToggled[index] = !isThinkingExpanded(props.messages[index], index)
}

// 该步骤是否「进行中」：正在连接、且是最后一条消息的最后一个步骤 → 显示转圈，否则显示完成
const isStepRunning = (stepIndex, msg, index) => {
  return props.connectionStatus === 'connecting'
    && index === props.messages.length - 1
    && stepIndex === msg.steps.length - 1
}

// 根据AI类型选择不同头像
const aiAvatar = computed(() => {
  return props.aiType === 'interview' 
    ? '/ai-interview-avatar.png'  // 智能面试官头像
    : '/ai-super-avatar.png' // 超级智能体头像
})

// 发送消息
const sendMessage = () => {
  if (!inputMessage.value.trim()) return
  
  emit('send-message', inputMessage.value)
  inputMessage.value = ''
}

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// 自动滚动到底部
const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 监听消息变化与内容变化，自动滚动
watch(() => props.messages.length, () => {
  scrollToBottom()
})

watch(() => props.messages.map(m => m.content).join(''), () => {
  scrollToBottom()
})

onMounted(() => {
  scrollToBottom()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 70vh;
  min-height: 600px;
  background-color: #f5f5f5;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  padding-bottom: 80px; /* 为输入框留出空间 */
  display: flex;
  flex-direction: column;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 72px; /* 与输入框高度相匹配 */
}

.message-wrapper {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  width: 100%;
}

.message {
  display: flex;
  align-items: flex-start;
  max-width: 85%;
  margin-bottom: 8px;
}

.user-message {
  margin-left: auto; /* 用户消息靠右 */
  flex-direction: row; /* 正常顺序，先气泡后头像 */
}

.ai-message {
  margin-right: auto; /* AI消息靠左 */
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-avatar {
  margin-left: 8px; /* 用户头像在右侧，左边距 */
}

.ai-avatar {
  margin-right: 8px; /* AI头像在左侧，右边距 */
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #007bff;
  color: white;
  font-weight: bold;
}

.message-bubble {
  padding: 12px;
  border-radius: 18px;
  position: relative;
  word-wrap: break-word;
  min-width: 100px; /* 最小宽度 */
}

.user-message .message-bubble {
  background-color: #007bff;
  color: white;
  border-bottom-right-radius: 4px;
  text-align: left;
}

.ai-message .message-bubble {
  background-color: #e9e9eb;
  color: #333;
  border-bottom-left-radius: 4px;
  text-align: left;
}

.message-content {
  font-size: 16px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.message-time {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 4px;
  text-align: right;
}

.chat-input-container {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: white;
  border-top: 1px solid #e0e0e0;
  z-index: 100;
  height: 72px; /* 固定高度 */
  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.05);
}

.chat-input {
  display: flex;
  padding: 16px;
  height: 100%;
  box-sizing: border-box;
  align-items: center;
}

.input-box {
  flex-grow: 1;
  border: 1px solid #ddd;
  border-radius: 20px;
  padding: 10px 16px;
  font-size: 16px;
  resize: none;
  min-height: 20px;
  max-height: 40px; /* 限制高度 */
  outline: none;
  transition: border-color 0.3s;
  overflow-y: auto;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE & Edge */
}

/* 隐藏Webkit浏览器的滚动条 */
.input-box::-webkit-scrollbar {
  display: none;
}

.input-box:focus {
  border-color: #007bff;
}

.send-button {
  margin-left: 12px;
  background-color: #007bff;
  color: white;
  border: none;
  border-radius: 20px;
  padding: 0 20px;
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.3s;
  height: 40px;
  align-self: center;
}

.send-button:hover:not(:disabled) {
  background-color: #0069d9;
}

.typing-indicator {
  display: inline-block;
  animation: blink 0.7s infinite;
  margin-left: 2px;
}

@keyframes blink {
  0% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

.input-box:disabled, .send-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message {
    max-width: 95%;
  }

  .thinking-panel {
    max-width: 95%;
    margin-left: 40px;
  }

  .message-content {
    font-size: 15px;
  }
  
  .chat-input {
    padding: 12px;
  }
  
  .input-box {
    padding: 8px 12px;
  }
  
  .send-button {
    padding: 0 15px;
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .avatar {
    width: 32px;
    height: 32px;
  }
  
  .message-bubble {
    padding: 10px;
  }
  
  .message-content {
    font-size: 14px;
  }
  
  .chat-input-container {
    height: 64px;
  }
  
  .chat-messages {
    bottom: 64px;
  }
}

/* 新增：不同类型消息的样式 */
.ai-answer {
  animation: fadeIn 0.3s ease-in-out;
}

.ai-final {
  /* 最终回答，可以有不同的样式，例如边框高亮等 */
}

.ai-error {
  opacity: 0.7;
}

.user-question {
  /* 用户提问的特殊样式 */
}

/* 连续消息气泡样式 */
.ai-message + .ai-message {
  margin-top: 4px;
}

.ai-message + .ai-message .avatar {
  visibility: hidden;
}

.ai-message + .ai-message .message-bubble {
  border-top-left-radius: 10px;
}

/* ===== 思考链面板 ===== */
.thinking-panel {
  max-width: 85%;
  margin-left: 44px; /* 与 AI 头像右侧气泡对齐 */
  margin-bottom: 6px;
  background-color: #f0f6ff;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  overflow: hidden;
  font-size: 13px;
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  color: #1e40af;
  user-select: none;
}

.thinking-title {
  font-weight: 600;
}

.thinking-count {
  color: #64748b;
  font-size: 12px;
}

.thinking-chevron {
  margin-left: auto;
  color: #64748b;
  transition: transform 0.2s;
}

.thinking-chevron.expanded {
  transform: rotate(180deg);
}

.thinking-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 12px 10px 12px;
}

.thinking-step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #334155;
  line-height: 1.5;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 7px;
}

.step-dot.done {
  background-color: #22c55e;
}

.step-dot.running {
  width: 10px;
  height: 10px;
  margin-top: 6px;
  background: transparent;
  border: 2px solid #2563eb;
  border-top-color: transparent;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.step-text {
  flex: 1;
}
</style> 