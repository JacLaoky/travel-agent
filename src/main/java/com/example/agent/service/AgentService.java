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

// ════════════════════════════════════════════════════════════════
//  Agent 核心：实现 ReAct 循环
//
//  流程：
//  用户问题
//    → 发给 DeepSeek（附带工具定义）
//    → 如果返回 tool_calls → 执行对应工具 → 把结果加入对话 → 再次发给 DeepSeek
//    → 如果返回 content（普通文本）→ 这是最终答案，返回给用户
//
//  类比 Python：就是一个 while 循环，不停和 LLM 对话直到它不再要工具
// ════════════════════════════════════════════════════════════════

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    // @Value 从 application.properties 注入配置
    // 类比 Python：os.environ.get('DEEPSEEK_API_KEY')
    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.model}")
    private String model;

    // Spring 自动注入 ToolService，不需要手动 new
    // 类比 Python：self.tool_service = ToolService()，但 Spring 帮你做
    private final ToolService toolService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // 构造函数注入（推荐方式，比 @Autowired 更清晰）
    public AgentService(ToolService toolService) {
        this.toolService = toolService;
    }

    // ─────────────────────────────────────────────────────────
    // 主入口：接收用户问题，返回 Agent 最终回答
    // ─────────────────────────────────────────────────────────
    // 用一个简单的数组包装 int，方便在 lambda 里修改
    // （Java lambda 里不能修改普通局部变量，这是 Java 的限制）
    public record AgentResult(String answer, int toolCallCount) {}

    public AgentResult chat(String userMessage) throws Exception {

        // messages 是对话历史，每轮都要带上
        // 类比 Python：messages = [{"role": "system", ...}, {"role": "user", ...}]
        List<ObjectNode> messages = new ArrayList<>();

        // System prompt：告诉 Agent 它的角色和能力
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content",
            "你是一个专业的旅行价格助手。用户询问旅行相关问题时，" +
            "主动使用工具查询实时数据（汇率、天气、航班），" +
            "综合所有信息给出具体的旅行建议。回答用中文。"
        );
        messages.add(systemMsg);

        // 用户消息
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        // ── ReAct 循环 ──────────────────────────────────────
        int toolCallCount = 0;   // 工具调用总次数（返回给前端展示）
        int iterations    = 0;   // 循环轮次（防止无限循环）
        int maxIterations = 8;

        while (iterations++ < maxIterations) {

            // 1. 调用 DeepSeek API
            String responseBody = callDeepSeek(messages);
            JsonNode response = objectMapper.readTree(responseBody);
            JsonNode choice = response.get("choices").get(0);
            JsonNode message = choice.get("message");

            String finishReason = choice.get("finish_reason").asText();
            log.debug("finish_reason: {}, toolCallCount: {}", finishReason, toolCallCount);

            // 2. 如果是最终答案（没有工具调用）→ 直接返回
            if ("stop".equals(finishReason) || !message.has("tool_calls")) {
                return new AgentResult(message.get("content").asText(), toolCallCount);
            }

            // 3. 有工具调用 → 执行工具，把结果加回对话
            // 先把 assistant 的这条消息（含 tool_calls）加入历史
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            if (message.has("content") && !message.get("content").isNull()) {
                assistantMsg.put("content", message.get("content").asText());
            }
            assistantMsg.set("tool_calls", message.get("tool_calls"));
            messages.add(assistantMsg);

            // 遍历每个工具调用并执行
            for (JsonNode toolCall : message.get("tool_calls")) {
                String toolCallId  = toolCall.get("id").asText();
                String toolName    = toolCall.get("function").get("name").asText();
                String toolArgsStr = toolCall.get("function").get("arguments").asText();
                JsonNode toolArgs  = objectMapper.readTree(toolArgsStr);

                log.debug("调用工具: {} 参数: {}", toolName, toolArgsStr);

                // 4. 根据工具名称执行对应方法
                // 类比 Python：result = getattr(tool_service, tool_name)(**args)
                String toolResult = executeTool(toolName, toolArgs);
                toolCallCount++;

                log.debug("工具结果: {}", toolResult);

                // 5. 把工具结果加入对话历史，角色是 "tool"
                ObjectNode toolResultMsg = objectMapper.createObjectNode();
                toolResultMsg.put("role", "tool");
                toolResultMsg.put("tool_call_id", toolCallId);
                toolResultMsg.put("content", toolResult);
                messages.add(toolResultMsg);
            }
            // 循环继续：把工具结果发回给 DeepSeek，让它决定下一步
        }

        return new AgentResult("抱歉，处理超时，请换个方式提问。", toolCallCount);
    }

    // ─────────────────────────────────────────────────────────
    // 工具分发器：根据名字调用对应的工具方法
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

        // 构建请求体（JSON）
        // 类比 Python：payload = {"model": "...", "messages": [...], "tools": [...]}
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        // 把 messages List 转成 JSON 数组
        ArrayNode messagesArray = objectMapper.createArrayNode();
        messages.forEach(messagesArray::add);
        requestBody.set("messages", messagesArray);

        // 工具定义：告诉 DeepSeek 有哪些工具可以用，每个工具的参数是什么
        requestBody.set("tools", buildToolDefinitions());

        String requestBodyStr = objectMapper.writeValueAsString(requestBody);
        log.debug("发送请求: {}", requestBodyStr);

        // 发 HTTP POST 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        log.debug("收到响应: {}", response.body());
        return response.body();
    }

    // ─────────────────────────────────────────────────────────
    // 工具定义：用 JSON 描述每个工具的名称、用途、参数
    // DeepSeek 根据这个定义决定要不要调用、怎么调用
    // 这是 Function Calling / Tool Use 的核心格式（OpenAI 兼容）
    // ─────────────────────────────────────────────────────────
    private ArrayNode buildToolDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();

        // 工具1：汇率查询
        ObjectNode exchangeProps = objectMapper.createObjectNode();
        exchangeProps.set("base_currency",   buildStringProp("基础货币代码，如 CNY、SGD、USD"));
        exchangeProps.set("target_currency", buildStringProp("目标货币代码，如 JPY、THB"));
        ObjectNode exchangeParams = objectMapper.createObjectNode();
        exchangeParams.put("type", "object");
        exchangeParams.set("properties", exchangeProps);
        exchangeParams.set("required", objectMapper.createArrayNode().add("base_currency").add("target_currency"));
        tools.add(buildTool("get_exchange_rate", "查询两种货币之间的实时汇率", exchangeParams));

        // 工具2：天气查询
        ObjectNode weatherProps = objectMapper.createObjectNode();
        weatherProps.set("city", buildStringProp("城市名称，如 Tokyo、Bangkok、首尔"));
        ObjectNode weatherParams = objectMapper.createObjectNode();
        weatherParams.put("type", "object");
        weatherParams.set("properties", weatherProps);
        weatherParams.set("required", objectMapper.createArrayNode().add("city"));
        tools.add(buildTool("get_weather", "查询目的地城市未来3天的天气预报", weatherParams));

        // 工具3：航班搜索
        ObjectNode flightProps = objectMapper.createObjectNode();
        flightProps.set("origin",      buildStringProp("出发城市，如 Singapore、北京"));
        flightProps.set("destination", buildStringProp("目的地城市，如 Tokyo、曼谷"));
        flightProps.set("date",        buildStringProp("出发日期，格式 YYYY-MM-DD"));
        ObjectNode flightParams = objectMapper.createObjectNode();
        flightParams.put("type", "object");
        flightParams.set("properties", flightProps);
        flightParams.set("required", objectMapper.createArrayNode().add("origin").add("destination").add("date"));
        tools.add(buildTool("search_flights", "搜索指定日期的航班和价格", flightParams));

        // 工具4：费用计算
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

    // 构建单个工具定义的辅助方法
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
