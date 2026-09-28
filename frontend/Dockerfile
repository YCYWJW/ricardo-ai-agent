# ============================================================
# 前端 Dockerfile（多阶段构建）
# 阶段一 build：Node 构建出静态产物 dist/
# 阶段二 运行：用 Nginx 托管静态文件，并反向代理 /api 到后端
# ============================================================

# ---------- 阶段一：构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 先拷贝依赖清单，利用 Docker 层缓存：依赖没变时不会重新安装
COPY package.json package-lock.json ./
# npm ci 依据 lock 文件安装，比 npm install 更快、更可复现
RUN npm ci

# 再拷贝源码并构建（node_modules / dist / .env 由 .dockerignore 排除）
COPY . .
RUN npm run build

# ---------- 阶段二：运行 ----------
FROM nginx:alpine

# 拷贝构建产物到 Nginx 静态目录
COPY --from=build /app/dist /usr/share/nginx/html

# 用自定义配置替换默认配置（含 SPA 路由回退 + /api 反向代理 + SSE 支持）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]