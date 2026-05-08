package com.example.agent.model;

public class ChatResponse {

    private String answer;
    private int toolCallCount;
    private int evalScore;

    public ChatResponse(String answer, int toolCallCount, int evalScore) {
        this.answer       = answer;
        this.toolCallCount = toolCallCount;
        this.evalScore    = evalScore;
    }

    public String getAnswer()      { return answer; }
    public int getToolCallCount()  { return toolCallCount; }
    public int getEvalScore()      { return evalScore; }
}
