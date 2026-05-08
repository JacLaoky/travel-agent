package com.example.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.model}")
    private String model;

    private final ToolService toolService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ── Session 存储 ──────────────────────────────────────────
    private final ConcurrentHashMap<String, List<ObjectNode>> sessions = new ConcurrentHashMap<>();

    // ── 历史压缩阈值 ──────────────────────────────────────────
    // 超过 COMPRESS_THRESHOLD 条消息时触发压缩
    // 压缩后保留 system prompt + summary + 最近 KEEP_RECENT 条
    private static final int COMPRESS_THRESHOLD = 24;
    private static final int KEEP_RECENT        = 8;

    public AgentService(ToolService toolService) {
        this.toolService = toolService;
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("Session cleared: {}", sessionId);
    }

    public record AgentResult(String answer, int toolCallCount) {}

    // ─────────────────────────────────────────────────────────
    // 主入口：支持多轮对话
    // ─────────────────────────────────────────────────────────
    public AgentResult chat(String userMessage, String sessionId) throws Exception {

        // 取出或新建该 session 的对话历史
        List<ObjectNode> messages = sessions.computeIfAbsent(sessionId, id -> {
            List<ObjectNode> history = new ArrayList<>();
            ObjectNode sys = objectMapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content",
                "你是一个专业的旅行价格助手。用户询问旅行相关问题时，" +
                "主动使用工具查询实时数据（汇率、天气、航班），" +
                "综合所有信息给出具体的旅行建议。回答用中文。"
            );
            history.add(sys);
            return history;
        });

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        log.debug("Session [{}] history size: {}", sessionId, messages.size());

        // AtomicInteger：线程安全计数器，供并行工具调用时累加
        AtomicInteger toolCallCount = new AtomicInteger(0);
        int iterations  = 0;
        int maxIterations = 8;

        while (iterations++ < maxIterations) {

            // ① 压缩过长的历史（可能调 LLM，放在每轮开头）
            compressHistoryIfNeeded(messages);

            String responseBody = callDeepSeek(messages);
            JsonNode response = objectMapper.readTree(responseBody);
            JsonNode choice  = response.get("choices").get(0);
            JsonNode message = choice.get("message");

            String finishReason = choice.get("finish_reason").asText();
            log.debug("finish_reason={}, toolCalls so far={}", finishReason, toolCallCount.get());

            // ② 最终答案：存入历史并返回
            if ("stop".equals(finishReason) || !message.has("tool_calls")) {
                String answer = message.get("content").asText();
                ObjectNode finalMsg = objectMapper.createObjectNode();
                finalMsg.put("role", "assistant");
                finalMsg.put("content", answer);
                messages.add(finalMsg);
                return new AgentResult(answer, toolCallCount.get());
            }

            // ③ 把 assistant 的工具调用意图存入历史
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            if (message.has("content") && !message.get("content").isNull()) {
                assistantMsg.put("content", message.get("content").asText());
            }
            assistantMsg.set("tool_calls", message.get("tool_calls"));
            messages.add(assistantMsg);

            // ④ 并行执行所有工具 ────────────────────────────────
            // 每个工具调用独立提交到 ForkJoinPool，互不等待
            // 类比 Python：asyncio.gather(*[call_tool(t) for t in tool_calls])
            List<CompletableFuture<ObjectNode>> futures = new ArrayList<>();

            for (JsonNode toolCall : message.get("tool_calls")) {
                final String toolCallId  = toolCall.get("id").asText();
                final String toolName    = toolCall.get("function").get("name").asText();
                final String toolArgsStr = toolCall.get("function").get("arguments").asText();

                CompletableFuture<ObjectNode> future = CompletableFuture.supplyAsync(() -> {
                    ObjectNode resultMsg = objectMapper.createObjectNode();
                    resultMsg.put("role", "tool");
                    resultMsg.put("tool_call_id", toolCallId);
                    try {
                        JsonNode toolArgs = objectMapper.readTree(toolArgsStr);
                        String result = executeTool(toolName, toolArgs);
                        log.debug("工具 [{}] 完成", toolName);
                        resultMsg.put("content", result);
                    } catch (Exception e) {
                        // 工具异常不崩溃整个请求，把错误信息返给 LLM，让它自己决定下一步
                        log.error("工具 [{}] 异常: {}", toolName, e.getMessage());
                        resultMsg.put("content", toolName + " 暂时不可用：" + e.getMessage());
                    }
                    return resultMsg;
                });
                futures.add(future);
            }

            // 等所有工具完成（按提交顺序收结果，保证 tool_call_id 匹配）
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<ObjectNode> f : futures) {
                messages.add(f.join());
                toolCallCount.incrementAndGet();
            }
        }

        return new AgentResult("抱歉，处理超时，请换个方式提问。", toolCallCount.get());
    }

    // ─────────────────────────────────────────────────────────
    // 对话历史压缩
    // 触发条件：消息数 > COMPRESS_THRESHOLD
    // 策略：把旧消息交给 LLM 总结，只保留摘要 + 最近 KEEP_RECENT 条
    // ─────────────────────────────────────────────────────────
    private void compressHistoryIfNeeded(List<ObjectNode> messages) {
        if (messages.size() <= COMPRESS_THRESHOLD) return;

        log.debug("Compressing history: {} → ~{} messages", messages.size(), 2 + KEEP_RECENT);

        ObjectNode systemMsg    = messages.get(0);
        int splitPoint          = messages.size() - KEEP_RECENT;
        List<ObjectNode> toCompress = new ArrayList<>(messages.subList(1, splitPoint));
        List<ObjectNode> toKeep     = new ArrayList<>(messages.subList(splitPoint, messages.size()));

        // 只提取 user / assistant 的文字消息（跳过 tool 结果，太长且不重要）
        StringBuilder sb = new StringBuilder("请将以下对话历史压缩成100字以内的中文摘要，保留关键查询信息和结果：\n\n");
        for (ObjectNode msg : toCompress) {
            String role = msg.get("role").asText();
            if (("user".equals(role) || "assistant".equals(role))
                    && msg.has("content") && !msg.get("content").isNull()) {
                String content = msg.get("content").asText();
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                sb.append("[").append(role).append("]: ").append(content).append("\n");
            }
        }

        try {
            String summary = callDeepSeekSimple(sb.toString());

            ObjectNode summaryMsg = objectMapper.createObjectNode();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "【之前对话摘要】" + summary);

            messages.clear();
            messages.add(systemMsg);
            messages.add(summaryMsg);
            messages.addAll(toKeep);

            log.debug("Compressed to {} messages", messages.size());

        } catch (Exception e) {
            // 压缩失败就直接截断，至少不会 OOM
            log.warn("历史压缩失败，直接截断: {}", e.getMessage());
            messages.clear();
            messages.add(systemMsg);
            messages.addAll(toKeep);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 不带工具的简单 DeepSeek 调用（用于历史压缩）
    // ─────────────────────────────────────────────────────────
    private String callDeepSeekSimple(String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        ArrayNode messagesArray = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", userPrompt);
        messagesArray.add(msg);
        requestBody.set("messages", messagesArray);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body())
                .get("choices").get(0).get("message").get("content").asText();
    }

    // ─────────────────────────────────────────────────────────
    // 工具分发（switch → ToolService）
    // ─────────────────────────────────────────────────────────
    private String executeTool(String toolName, JsonNode args) {
        return switch (toolName) {
            case "get_exchange_rate" -> toolService.getExchangeRate(
                args.get("base_currency").asText(),
                args.get("target_currency").asText()
            );
            case "get_weather" -> toolService.getWeather(
                args.get("city").asText()
            );
            case "search_flights" -> toolService.searchFlights(
                args.get("origin").asText(),
                args.get("destination").asText(),
                args.get("date").asText()
            );
            case "calculate_trip_cost" -> toolService.calculateTripCost(
                args.get("flight_price").asDouble(),
                args.get("hotel_per_night").asDouble(),
                args.get("nights").asInt(),
                args.get("currency").asText("CNY")
            );
            default -> "未知工具：" + toolName;
        };
    }

    // ─────────────────────────────────────────────────────────
    // 调用 DeepSeek API（带工具定义）
    // ─────────────────────────────────────────────────────────
    private String callDeepSeek(List<ObjectNode> messages) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        ArrayNode messagesArray = objectMapper.createArrayNode();
        messages.forEach(messagesArray::add);
        requestBody.set("messages", messagesArray);
        requestBody.set("tools", buildToolDefinitions());

        String requestBodyStr = objectMapper.writeValueAsString(requestBody);
        log.debug("发送请求 ({} messages)", messages.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("收到响应: {}", response.body());
        return response.body();
    }

    // ─────────────────────────────────────────────────────────
    // 工具定义（JSON Schema，告诉 DeepSeek 有哪些工具可用）
    // ─────────────────────────────────────────────────────────
    private ArrayNode buildToolDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();

        ObjectNode exchangeProps = objectMapper.createObjectNode();
        exchangeProps.set("base_currency",   buildStringProp("基础货币代码，如 CNY、SGD、USD"));
        exchangeProps.set("target_currency", buildStringProp("目标货币代码，如 JPY、THB"));
        ObjectNode exchangeParams = objectMapper.createObjectNode();
        exchangeParams.put("type", "object");
        exchangeParams.set("properties", exchangeProps);
        exchangeParams.set("required", objectMapper.createArrayNode().add("base_currency").add("target_currency"));
        tools.add(buildTool("get_exchange_rate", "查询两种货币之间的实时汇率", exchangeParams));

        ObjectNode weatherProps = objectMapper.createObjectNode();
        weatherProps.set("city", buildStringProp("城市名称，如 Tokyo、Bangkok、首尔"));
        ObjectNode weatherParams = objectMapper.createObjectNode();
        weatherParams.put("type", "object");
        weatherParams.set("properties", weatherProps);
        weatherParams.set("required", objectMapper.createArrayNode().add("city"));
        tools.add(buildTool("get_weather", "查询目的地城市未来3天的天气预报", weatherParams));

        ObjectNode flightProps = objectMapper.createObjectNode();
        flightProps.set("origin",      buildStringProp("出发城市，如 Singapore、北京"));
        flightProps.set("destination", buildStringProp("目的地城市，如 Tokyo、曼谷"));
        flightProps.set("date",        buildStringProp("出发日期，格式 YYYY-MM-DD"));
        ObjectNode flightParams = objectMapper.createObjectNode();
        flightParams.put("type", "object");
        flightParams.set("properties", flightProps);
        flightParams.set("required", objectMapper.createArrayNode().add("origin").add("destination").add("date"));
        tools.add(buildTool("search_flights", "搜索指定日期的航班和价格", flightParams));

        ObjectNode costProps = objectMapper.createObjectNode();
        costProps.set("flight_price",    buildStringProp("机票价格（数字）"));
        costProps.set("hotel_per_night", buildStringProp("酒店每晚价格（数字）"));
        costProps.set("nights",          buildStringProp("住宿天数（整数）"));
        costProps.set("currency",        buildStringProp("货币单位，如 CNY"));
        ObjectNode costParams = objectMapper.createObjectNode();
        costParams.put("type", "object");
        costParams.set("properties", costProps);
        costParams.set("required", objectMapper.createArrayNode().add("flight_price").add("hotel_per_night").add("nights"));
        tools.add(buildTool("calculate_trip_cost", "计算旅行总费用（机票+酒店）", costParams));

        return tools;
    }

    private ObjectNode buildTool(String name, String description, ObjectNode parameters) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        tool.set("function", function);
        return tool;
    }

    private ObjectNode buildStringProp(String description) {
        ObjectNode prop = objectMapper.createObjectNode();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }
}
