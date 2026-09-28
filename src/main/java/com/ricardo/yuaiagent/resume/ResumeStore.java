package com.ricardo.yuaiagent.resume;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简历内存缓存：以 chatId 为 key 保存候选人上传的简历文本。
 * 纯内存存储、不落盘，应用重启后自动清空。
 * 用 ConcurrentHashMap 是为了线程安全（即使将来多个会话并发上传也不会出问题）。
 */
@Component
public class ResumeStore {

    private final Map<String, String> resumes = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖某个会话的简历文本（后传覆盖旧的）
     *
     * @param chatId     会话 ID
     * @param resumeText 简历文本
     */
    public void put(String chatId, String resumeText) {
        resumes.put(chatId, resumeText);
    }

    /**
     * 获取某个会话的简历文本
     *
     * @param chatId 会话 ID
     * @return 简历文本，不存在时返回 null
     */
    public String get(String chatId) {
        return resumes.get(chatId);
    }
}