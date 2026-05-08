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
    // 类比 Flask：@app.route('/api/agent/chat', methods=['POST'])
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            AgentService.AgentResult result = agentService.chat(request.getMessage());
            return ResponseEntity.ok(new ChatResponse(result.answer(), result.toolCallCount()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("服务器错误：" + e.getMessage(), 0));
        }
    }

    // GET /api/agent/health — 健康检查，确认服务在跑
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Travel Agent is running ✈️");
    }
}
