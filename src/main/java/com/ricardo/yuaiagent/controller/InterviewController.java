package com.ricardo.yuaiagent.controller;

import com.ricardo.yuaiagent.resume.ResumeStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
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

    // 单文件大小上限：5MB（application.yml 里的 multipart 限制也是 5MB，这里再兜一道）
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    // 页数上限：正常简历也就 1~3 页，50 页已经远超合理范围，基本可判定是恶意构造或误传
    private static final int MAX_PAGE_COUNT = 50;

    // 抽出文字的长度上限：20 万字符（约 10 万汉字），足够装下任何正常简历。
    // 防的是「页数没超，但每页都塞满字」的情况——那会把后面拼给大模型的 prompt 撑爆
    private static final int MAX_TEXT_LENGTH = 200_000;

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
        } catch (ResumeRejectedException e) {
            // 预料之内的拒绝（页数超了 / 文字太长）：把具体原因原样告诉用户，
            // 不能混进下面那句「文件可能损坏」里，否则用户会一头雾水
            log.warn("简历不符合受理条件，chatId={}，原因={}", chatId, e.getMessage());
            return fail(e.getMessage());
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
     * 用 PDFBox 抽取 PDF 中的文字层文本。
     * <p>
     * 两道防线，缺一不可：
     * <p>
     * 1）用「只落临时文件」的流缓存。
     * PDFBox 3 默认的 {@code RandomAccessStreamCacheImpl} 是纯内存实现，解压出来的内容全留在堆里。
     * 而 PDF 的压缩比可以高得离谱，5MB 的恶意文件解压后能涨到几百 MB，直接把 JVM 内存打满。
     * 换成只写临时文件后，堆内存几乎不随文件变大而增长。
     * <p>
     * 2）先看页数，再抽文本。
     * 注意这个顺序：内存爆炸其实发生在 {@code loadPDF} 解析阶段，光靠页数拦不住它
     * （因为得先把文档加载完才知道有几页），真正防住内存的是上面那条。
     * 页数检查的价值在于拦住 CPU 和时间开销——{@code getText()} 是最耗时的一步，
     * 在它之前把上万页的文件挡掉，就不用白跑一遍。
     */
    private String extractText(MultipartFile file) throws IOException {
        // 把上传的字节包成 PDFBox 的随机读取源，再连同「只落临时文件」的缓存交给 loadPDF。
        // 说明：PDFBox 3 里没有 loadPDF(byte[], StreamCacheCreateFunction) 这个重载，
        // 带 byte[] 的那条必须再传密码、密钥库等参数，用起来要填一串 null，所以改用这种写法。
        // 上传的字节本身留在内存（只有 5MB，有上限），落到临时文件的是解压后的内容 —— 那才是会炸内存的部分。
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getBytes()),
                IOUtils.createTempFileOnlyStreamCache())) {
            // 2.1 页数检查：放在 getText() 之前
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PAGE_COUNT) {
                throw new ResumeRejectedException(
                        "简历页数过多（" + pageCount + " 页，上限 " + MAX_PAGE_COUNT + " 页），请精简后重试");
            }
            // 2.2 抽取文字
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            // 2.3 文字长度检查：防止「页数没超但每页塞满字」把后续 prompt 撑爆
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new ResumeRejectedException(
                        "简历文字内容过长（" + text.length() + " 字符，上限 " + MAX_TEXT_LENGTH + " 字符），请精简后重试");
            }
            return text;
        }
    }

    /**
     * 简历不符合受理条件（页数过多 / 文字过长）时抛出。
     * <p>
     * 单独定义这个异常，是为了让调用方能把它和「文件损坏、加密、解析失败」区分开：
     * 前者要给用户一个明确的原因，后者才用那句笼统的「解析失败，请确认文件未损坏」。
     */
    private static class ResumeRejectedException extends RuntimeException {
        ResumeRejectedException(String message) {
            super(message);
        }
    }

    /**
     * 组装失败响应
     */
    private Map<String, Object> fail(String message) {
        return Map.of("success", false, "message", message);
    }
}