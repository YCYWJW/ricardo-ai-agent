# ricardo-ai-agent

> 一个基于 SpringBoot + Spring AI 的 AI 智能体应用，支持多轮对话、RAG 知识库、工具调用、MCP 服务等核心能力。

## 项目简介

ricardo-ai-agent 是一个面向 AI 应用开发的实战项目，通过接入阿里云百炼（DashScope）的大模型服务，实现了一个可扩展的 AI 智能体框架。项目涵盖了多轮对话、RAG 检索增强、工具调用、MCP 服务、SSE 流式输出等主流 AI 应用能力，适合作为学习 Spring AI 和 AI 应用架构的参考项目。

## 技术栈

**后端**
- SpringBoot 3
- Spring AI 1.0
- Spring AI Alibaba（百炼集成）
- PgVector（向量数据库）
- SSE（流式输出）

**前端**
- Vue 3
- Element Plus
- Axios

**AI 能力**
- 阿里云百炼（DashScope）大模型服务
- RAG 检索增强生成
- MCP（Model Context Protocol）服务
- 工具调用（Function Calling）

**工程化**
- JDK 21
- Maven
- Docker / Nginx
- dotenv-java（环境变量管理）

## 核心功能

### 1. 多轮对话
基于 Spring AI 的 ChatClient 实现多轮对话，支持上下文记忆、流式输出，用户可以连续追问、切换话题。

### 2. RAG 知识库
- 加载本地文档（Markdown / TXT / PDF）
- 基于 BGE-M3 向量化，存入 PgVector
- 检索时采用 similarity_search_with_score + 距离排序，减少无关引用
- 支持引用溯源，回答中附带原始文档片段

### 3. 工具调用
- 内置多种实用工具：文件操作、PDF 生成、网页抓取、资源下载、终端命令等
- 基于 Spring AI 的 Function Calling 机制，AI 可根据用户意图自动选择工具

### 4. MCP 服务
- 内置独立的 MCP 服务模块，支持以 stdio / SSE 两种模式启动
- 可作为独立的 AI 工具服务被其他应用调用

### 5. 恋爱大师 / 超级智能体
- 基于 System Prompt 的角色扮演，实现恋爱咨询师人格
- 超级智能体支持多步推理、工具编排、任务规划

## 项目结构

```
ricardo-ai-agent/
├── src/                    主工程
│   ├── main/java/com/ricardo/yuaiagent/
│   │   ├── agent/          AI 智能体（ReActAgent、ToolCallAgent、RicardoManus）
│   │   ├── app/            应用入口（LoveApp）
│   │   ├── chatmemory/     会话记忆（基于文件）
│   │   ├── config/         配置类
│   │   ├── controller/     接口层
│   │   ├── demo/           演示类
│   │   ├── rag/            RAG 检索增强
│   │   └── tools/          工具集
│   └── main/resources/
│       ├── application.yml 主配置
│       ├── document/       知识库文档
│       └── mcp-servers.json MCP 服务配置
├── mcp-server/             MCP 服务模块
└── frontend/               前端工程
```

## 本地部署

### 前置依赖

- JDK 21（命令行与 IDEA 均需指向 21）
- Maven 3.9+
- PostgreSQL 16 + PgVector 扩展
- 阿里云百炼账号 + API Key

### 步骤

1. 克隆项目

```
git clone https://github.com/YCYWJW/ricardo-ai-agent.git
cd ricardo-ai-agent
```

2. 配置环境变量

在项目根目录创建 .env 文件，内容：

```
DASHSCOPE_API_KEY=你的百炼API Key
```

3. 初始化数据库

在 PostgreSQL 中创建数据库，并启用 PgVector 扩展：

```
CREATE DATABASE ricardo_ai_agent;
\c ricardo_ai_agent
CREATE EXTENSION vector;
```

4. 修改 application.yml

配置数据库连接、向量库参数等：

```
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ricardo_ai_agent
    username: postgres
    password: 你的密码
```

5. 启动后端

```
mvn clean install -DskipTests
mvn spring-boot:run
```

启动成功后，日志会输出：

```
已从 .env 加载变量：DASHSCOPE_API_KEY
Started RicardoAiAgentApplication in x.xxx seconds
```

6. 启动前端

```
cd frontend
npm install
npm run dev
```

7. 访问应用

浏览器打开 http://localhost:5173。

## 项目亮点

1. RAG 检索优化：采用 similarity_search_with_score + 距离排序，K 值从 4 降至 2，显著减少无关引用。
2. 工具调用框架：基于 Spring AI Function Calling，内置文件操作、PDF 生成、网页抓取、资源下载等工具，AI 可根据用户意图自主选择。
3. MCP 服务化：将图片搜索能力封装为独立 MCP 服务，支持 stdio / SSE 双模式，可被任意 AI 应用调用。
4. 环境变量安全：API Key 通过 .env 管理，dotenv-java 启动时注入为系统属性，不硬编码、不提交至 Git。
5. 流式输出：基于 SSE 实现前端实时显示 AI 回复，提升交互体验。

## 作者

李嘉图（Ricardo）

- GitHub: https://github.com/YCYWJW
- Email: 3505498783@qq.com

## License

MIT