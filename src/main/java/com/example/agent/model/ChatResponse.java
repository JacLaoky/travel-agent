package com.example.agent.model;

public class ChatResponse {

    private String answer;
    private int toolCallCount;

    // 构造函数：类比 Python 的 __init__
    public ChatResponse(String answer, int toolCallCount) {
        this.answer = answer;
        this.toolCallCount = toolCallCount;
    }

    public String getAnswer()      { return answer; }
    public int getToolCallCount()  { return toolCallCount; }
}
