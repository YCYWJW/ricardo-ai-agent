"""任务3：AI 面试官 —— 简历 PDF 上传接口压测（POST /api/interview/upload）。

【为什么这个接口可以放心压：它完全不调大模型】
看 InterviewController.uploadResume 的实现，整个链路只做 4 件事：
  校验文件 → PDFBox 抽取文字 → 存进内存缓存（ResumeStore，按 chatId）→ 返回安全摘要。
没有任何一处调用 DashScope / Ollama / 向量库，所以不会消耗 API Key、不烧钱、不会被限流。
它的负载特征是「纯 CPU 密集」（PDF 文字抽取），并发压力最终会顶在 CPU 上。

【压测素材】
默认用一份 66KB、可抽取约 1.2 万字的中文/英文文档 PDF（单次解析约 130ms）。
PDF 字节在模块加载时一次性读进内存，避免把客户端磁盘 IO 混进服务端指标。
想换素材：PDF_PATH=路径 覆盖即可。

用法（在 d:/yu-ai-agent 目录下运行）：
    "D:/RAG-Knowledge-Base/backend/venv/Scripts/python.exe" -m locust \
      -f stress/interview_upload_test.py --headless --host http://127.0.0.1:8123 \
      -u 20 -r 4 --run-time 30s --csv=stress/result_upload --only-summary
"""
import itertools
import os
from pathlib import Path

from locust import HttpUser, between, task

# 压测用的简历 PDF：默认取 Erlang 文档里的一份文字版 PDF（66KB，可抽出约 1.2 万字）
PDF_PATH = os.getenv(
    "PDF_PATH",
    "D:/code/Tools/Erlang/lib/eldap-1.2.12/doc/pdf/eldap-1.2.12.pdf",
)
# 文件内容只读一次，之后每次请求复用内存里的字节，测的是纯服务端解析耗时
PDF_BYTES = Path(PDF_PATH).read_bytes()
PDF_NAME = Path(PDF_PATH).name

# 每个虚拟用户一个独立 chatId，模拟「每个人上传自己的简历」
_seq = itertools.count()


class UploadUser(HttpUser):
    """只打简历上传接口的虚拟用户。"""

    host = "http://127.0.0.1:8123"
    wait_time = between(0, 0)

    def on_start(self):
        """给这个虚拟用户一个固定的 chatId（会话 ID）。"""
        self.chat_id = f"stress-{next(_seq):04d}"

    @task
    def upload(self):
        """上传简历：HTTP 200 且返回体 success=true 才算成功。"""
        with self.client.post(
            "/api/interview/upload",
            files={"file": (PDF_NAME, PDF_BYTES, "application/pdf")},
            data={"chatId": self.chat_id},
            name="/api/interview/upload",
            catch_response=True,
        ) as r:
            if r.status_code == 200 and '"success":true' in r.text.replace(" ", ""):
                r.success()
            elif r.status_code == 200:
                # 200 但 success 不是 true：说明被业务校验挡下了（如大小超限、非 PDF）
                r.failure(f"业务失败：{r.text[:120]}")
            else:
                r.failure(f"状态码 {r.status_code}")