package com.example.agent.controller;

import com.example.agent.model.ChatRequest;
import com.example.agent.model.ChatResponse;
import com.example.agent.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// @RestController = @Controller + @ResponseBody
// 类比 Flask：@app.route(...)，自动把返回值序列化成 JSON
@RestController
@RequestMapping("/api/agent")   // 所有方法的 URL 前缀
@CrossOrigin(origins = "*")     // 允许跨域，类比 Flask-CORS
public class AgentController {

    // Spring 自动注入 AgentService
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    // POST /api/agent/chat
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            // sessionId 为空时用 "default"，保证向后兼容
            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId()
                    : "default";
            AgentService.AgentResult result = agentService.chat(request.getMessage(), sessionId);
            return ResponseEntity.ok(new ChatResponse(result.answer(), result.toolCallCount(), result.evalScore()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("服务器错误：" + e.getMessage(), 0, 0));
        }
    }

    // DELETE /api/agent/session/{sessionId} — 清除指定会话历史（开始新对话）
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<String> clearSession(@PathVariable String sessionId) {
        agentService.clearSession(sessionId);
        return ResponseEntity.ok("Session cleared: " + sessionId);
    }

    // GET /api/agent/health — 健康检查，确认服务在跑
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Travel Agent is running ✈️");
    }
}
