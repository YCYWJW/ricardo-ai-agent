import axios from 'axios'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production' 
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务

// 创建axios实例
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

// 封装SSE连接
// onMessage: 纯文本回调（兼容旧协议，含 [DONE] 结束标记）
// onError:   错误回调
// onEvent:   结构化事件回调，收到形如 {"type":"thought","content":"..."} 的 JSON 事件时触发
export const connectSSE = (url, params, onMessage, onError, onEvent) => {
  // 构建带参数的URL
  const queryString = Object.keys(params)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')

  const fullUrl = `${API_BASE_URL}${url}?${queryString}`

  // 创建EventSource
  const eventSource = new EventSource(fullUrl)

  eventSource.onmessage = event => {
    const data = event.data

    // 旧协议：结束标记，保持向后兼容
    if (data === '[DONE]') {
      if (onMessage) onMessage('[DONE]')
      return
    }

    // 新协议：尝试解析结构化 JSON 事件（thought / text / tool / done）
    if (onEvent) {
      try {
        const parsed = JSON.parse(data)
        if (parsed && typeof parsed === 'object' && parsed.type) {
          onEvent(parsed)
          return
        }
      } catch (e) {
        // 解析失败说明这不是 JSON，而是普通文本流 —— 交给下面的兜底回调
      }
    }

    // 兜底：普通文本流原样交给 onMessage，确保老页面（如超级智能体）不受影响
    if (onMessage) onMessage(data)
  }

  eventSource.onerror = error => {
    if (onError) onError(error)
    eventSource.close()
  }

  // 返回eventSource实例，以便后续可以关闭连接
  return eventSource
}

// AI智能面试官聊天（支持思考链结构化事件）
export const chatWithInterviewApp = (message, chatId, onMessage, onError, onEvent) => {
  return connectSSE('/ai/interview_app/chat/sse', { message, chatId }, onMessage, onError, onEvent)
}

// 上传简历 PDF（FormData 方式）
export const uploadResume = (chatId, file) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('chatId', chatId)
  // 用 axios 发送，浏览器会自动带上 multipart/form-data 的边界信息
  return request.post('/interview/upload', formData)
}

// AI超级智能体聊天
export const chatWithManus = (message) => {
  return connectSSE('/ai/manus/chat', { message })
}

export default {
  chatWithInterviewApp,
  chatWithManus,
  uploadResume
} 