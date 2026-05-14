package com.example.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

// ════════════════════════════════════════════════════════════════
//  长期记忆存储（Redis 持久化版）
//
//  短期记忆 = session 内的对话历史（AgentService 的 sessions map，内存）
//  长期记忆 = 跨 session 的用户偏好（本类，Redis 持久化，重启不丢失）
//
//  Redis key 格式：travel:memory:{userId}
//  Value：一句话自然语言描述，由 LLM 提取并维护
//  TTL：30天无活动自动过期
// ════════════════════════════════════════════════════════════════
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    // Redis key 前缀，避免和其他应用的 key 冲突
    private static final String KEY_PREFIX = "travel:memory:";

    // 记忆 TTL：30天不使用自动过期，防止无限积累
    private static final Duration TTL = Duration.ofDays(30);

    // Spring 自动注入的 Redis 客户端（操作 String 类型的 key-value）
    // 类比 Python：redis.StrictRedis(host='localhost', port=6379)
    private final StringRedisTemplate redis;

    // 构造函数注入（Spring Boot 自动配置 StringRedisTemplate，只需声明）
    public MemoryService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ─────────────────────────────────────────────────────────
    // 读取用户记忆
    // redis.opsForValue().get() 等价于 Python 的 redis_client.get(key)
    // ─────────────────────────────────────────────────────────
    public String get(String userId) {
        if (userId == null || userId.isBlank()) return "";
        try {
            String val = redis.opsForValue().get(KEY_PREFIX + userId);
            return val != null ? val : "";
        } catch (Exception e) {
            // Redis 不可用时降级为空记忆，不阻塞主流程
            log.warn("Redis read failed for [{}], fallback to empty: {}", userId, e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────
    // 更新用户记忆（由 AgentService 异步调用）
    // set(key, value, ttl) 同时写入值并刷新过期时间
    // ─────────────────────────────────────────────────────────
    public void update(String userId, String newMemory) {
        if (userId == null || userId.isBlank()) return;
        if (newMemory == null || newMemory.isBlank()) return;
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, newMemory, TTL);
            log.debug("Memory updated [{}]: {}", userId, newMemory);
        } catch (Exception e) {
            // Redis 写失败只记日志，不影响主流程
            log.warn("Redis write failed for [{}]: {}", userId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // 清除用户记忆（供 DELETE /api/agent/memory/{userId} 调用）
    // ─────────────────────────────────────────────────────────
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            redis.delete(KEY_PREFIX + userId);
            log.debug("Memory cleared: {}", userId);
        } catch (Exception e) {
            log.warn("Redis delete failed for [{}]: {}", userId, e.getMessage());
        }
    }
}
