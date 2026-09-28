package com.ricardo.yuaiagent.controller;

import com.ricardo.yuaiagent.resume.ResumeStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 面试官相关接口（简历 PDF 上传与解析）。
 * 解析出的简历文本只存内存缓存（按 chatId），不落盘、不进日志。
 */
@RestController
@RequestMapping("/interview")
@Slf4j
public class InterviewController {

    // 单文件大小上限：5MB
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    @Resource
    private ResumeStore resumeStore;

    /**
     * 简历 PDF 上传与解析接口
     *
     * @param file   上传的 PDF 文件
     * @param chatId 会话 ID
     * @return 处理结果（只返回安全摘要，绝不返回/打印简历正文）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadResume(@RequestParam("file") MultipartFile file,
                                            @RequestParam("chatId") String chatId) {
        // 1. 校验文件非空
        if (file == null || file.isEmpty()) {
            return fail("上传的简历文件为空，请检查后重试");
        }
        // 2. 校验大小不超过 5MB
        if (file.getSize() > MAX_FILE_SIZE) {
            return fail("简历文件超过 5MB 上限，请压缩后重试");
        }
        // 3. 校验只接受 PDF（通过文件后缀 + Content-Type 双重判断）
        if (!isPdf(file)) {
            return fail("只支持 PDF 格式的简历文件");
        }
        // 4. 解析 PDF 抽取文本
        String text;
        try {
            text = extractText(file);
        } catch (Exception e) {
            // 只打印解析失败原因，不打印文件内容
            log.warn("PDF 解析失败，chatId={}，原因={}", chatId, e.getMessage());
            return fail("PDF 解析失败，请确认文件未损坏、未加密，且是文字版 PDF");
        }
        // 5. 校验确实抽到了文字
        if (text == null || text.isBlank()) {
            return fail("未能从该 PDF 中提取到文字，请确认是文字版 PDF（非扫描件/纯图片）");
        }
        // 6. 存入内存缓存（后传覆盖旧简历）
        resumeStore.put(chatId, text.trim());
        // 只打印安全摘要：长度 + chatId，绝不打印简历正文
        log.info("简历已解析，长度={}，已存入chatId={}", text.length(), chatId);
        return Map.of(
                "success", true,
                "message", "简历已就绪，开始面试吧",
                "length", text.length(),
                "chatId", chatId
        );
    }

    /**
     * 判断文件是否为 PDF（后缀 .pdf 或 Content-Type 为 application/pdf，满足其一即可）
     */
    private boolean isPdf(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            return true;
        }
        String contentType = file.getContentType();
        return contentType != null && "application/pdf".equalsIgnoreCase(contentType);
    }

    /**
     * 用 PDFBox 抽取 PDF 中的文字层文本
     */
    private String extractText(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 组装失败响应
     */
    private Map<String, Object> fail(String message) {
        return Map.of("success", false, "message", message);
    }
}