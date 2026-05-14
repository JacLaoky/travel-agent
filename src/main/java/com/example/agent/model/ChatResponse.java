package com.example.agent.model;

public class ChatResponse {

    private String answer;
    private int    toolCallCount;
    private int    evalScore;
    private String memoryUsed;   // 本次响应使用了哪条长期记忆（null = 未使用）

    public ChatResponse(String answer, int toolCallCount, int evalScore, String memoryUsed) {
        this.answer        = answer;
        this.toolCallCount = toolCallCount;
        this.evalScore     = evalScore;
        this.memoryUsed    = memoryUsed;
    }

    public String getAnswer()       { return answer; }
    public int    getToolCallCount(){ return toolCallCount; }
    public int    getEvalScore()    { return evalScore; }
    public String getMemoryUsed()   { return memoryUsed; }
}
