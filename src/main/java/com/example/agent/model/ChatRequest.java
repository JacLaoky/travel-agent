package com.example.agent.model;

// Java 的标准写法：字段私有，通过 getter/setter 访问
// 这就是 Lombok @Data 帮你自动生成的东西
// 类比 Python：就是一个普通 class，__init__ + 属性
public class ChatRequest {

    private String message;
    private String sessionId;
    private String userId;   // 跨 session 的稳定标识，用于长期记忆（可选）

    public String getMessage()   { return message; }
    public String getSessionId() { return sessionId; }
    public String getUserId()    { return userId; }

    public void setMessage(String message)     { this.message = message; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setUserId(String userId)       { this.userId = userId; }
}
