# ============================================================
# 后端 Dockerfile（多阶段构建）
# 阶段一 builder：用带 Maven + JDK21 的镜像编译打包
# 阶段二 runtime：只带 JRE，仅拷贝 jar，不含源码与 Maven 工具
# 目的：把镜像从 ~800MB 降到 ~250MB
# ============================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 先只拷贝 pom.xml 并预下载依赖：
# 这样只要 pom.xml 没变，重建镜像时会命中 Docker 层缓存，不会重新下载依赖
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷贝源码并打包（跳过测试，测试由 CI 负责）
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 只从构建阶段拷贝打好的 jar，绝不包含源码、pom.xml、Maven 工具
COPY --from=builder /app/target/*.jar app.jar

# 应用端口（与 application.yml 的 server.port 一致）
EXPOSE 8123

# 生产环境 profile 启动。
# 注意：.env 不会被拷进镜像，DASHSCOPE_API_KEY 由 docker-compose 的 env_file 在运行时注入，
#      启动时 DotenvConfig 找不到 .env 会给出提示并回退到系统环境变量（已做容错）。
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]