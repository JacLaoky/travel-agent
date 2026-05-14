package com.example.agent.controller;

import com.example.agent.model.ChatRequest;
import com.example.agent.model.ChatResponse;
import com.example.agent.service.AgentService;
import com.example.agent.service.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentService  agentService;
    private final MemoryService memoryService;

    public AgentController(AgentService agentService, MemoryService memoryService) {
        this.agentService  = agentService;
        this.memoryService = memoryService;
    }

    // POST /api/agent/chat
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId() : "default";
            String userId = request.getUserId();

            AgentService.AgentResult result = agentService.chat(request.getMessage(), sessionId, userId);
            return ResponseEntity.ok(new ChatResponse(
                    result.answer(), result.toolCallCount(), result.evalScore(), result.memoryUsed()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("服务器错误：" + e.getMessage(), 0, 0, null));
        }
    }

    // DELETE /api/agent/session/{sessionId} — 清除会话历史
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<String> clearSession(@PathVariable String sessionId) {
        agentService.clearSession(sessionId);
        return ResponseEntity.ok("Session cleared: " + sessionId);
    }

    // GET /api/agent/memory/{userId} — 查看用户长期记忆
    @GetMapping("/memory/{userId}")
    public ResponseEntity<String> getMemory(@PathVariable String userId) {
        String memory = memoryService.get(userId);
        return ResponseEntity.ok(memory.isBlank() ? "暂无记忆" : memory);
    }

    // DELETE /api/agent/memory/{userId} — 清除用户长期记忆
    @DeleteMapping("/memory/{userId}")
    public ResponseEntity<String> clearMemory(@PathVariable String userId) {
        memoryService.clear(userId);
        return ResponseEntity.ok("Memory cleared: " + userId);
    }

    // GET /api/agent/health
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Travel Agent is running ✈️");
    }
}
