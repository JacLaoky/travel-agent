package com.example.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolService {

    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 汇率缓存 ──────────────────────────────────────────────
    // API 失败时用上次成功的结果降级，不让 Agent 卡死
    // key: "SGD_JPY"  value: "1 SGD = 110.52 JPY（缓存汇率）"
    private final ConcurrentHashMap<String, String> rateCache = new ConcurrentHashMap<>();

    // ── 自定义函数式接口（支持受检异常，Java 内置 Supplier 不支持）──
    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    // ─────────────────────────────────────────────────────────
    // 重试辅助方法
    // 最多执行 maxRetries+1 次，失败后等待递增时间（300ms / 600ms）
    // 全部失败时返回 fallback（可为 null → 返回错误描述）
    // ─────────────────────────────────────────────────────────
    private String withRetry(String toolName, CheckedSupplier<String> action, String fallback) {
        int maxRetries = 2;
        Exception lastEx = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastEx = e;
                log.warn("[{}] 第{}次失败: {}", toolName, attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(300L * (attempt + 1));   // 300ms / 600ms 递增等待
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();    // 恢复中断状态，不吞掉
                    }
                }
            }
        }

        log.error("[{}] 所有重试失败", toolName, lastEx);
        // 有缓存值就降级，没有就返回友好错误信息
        return fallback != null ? fallback
                : toolName + " 暂时不可用，请稍后重试（" + (lastEx != null ? lastEx.getMessage() : "未知错误") + "）";
    }

    // ─────────────────────────────────────────────────────────
    // 工具 1：汇率查询（重试 + 缓存降级）
    // ─────────────────────────────────────────────────────────
    public String getExchangeRate(String baseCurrency, String targetCurrency) {
        String cacheKey = baseCurrency.toUpperCase() + "_" + targetCurrency.toUpperCase();

        return withRetry("汇率查询", () -> {
            String url = "https://api.exchangerate-api.com/v4/latest/" + baseCurrency.toUpperCase();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            double rate = root.get("rates").get(targetCurrency.toUpperCase()).asDouble();

            String result = String.format("1 %s = %.4f %s（实时汇率）",
                    baseCurrency.toUpperCase(), rate, targetCurrency.toUpperCase());

            rateCache.put(cacheKey, result.replace("实时汇率", "缓存汇率"));  // 存缓存（标记来源）
            return result;

        }, rateCache.get(cacheKey));  // 全部重试失败时，返回缓存值（可能为 null）
    }

    // ─────────────────────────────────────────────────────────
    // 工具 2：天气查询（重试，无缓存降级）
    // ─────────────────────────────────────────────────────────
    public String getWeather(String city) {
        String coords = switch (city.toLowerCase()) {
            case "tokyo",     "东京"   -> "35.6762,139.6503";
            case "osaka",     "大阪"   -> "34.6937,135.5023";
            case "bangkok",   "曼谷"   -> "13.7563,100.5018";
            case "singapore", "新加坡" -> "1.3521,103.8198";
            case "seoul",     "首尔"   -> "37.5665,126.9780";
            case "paris",     "巴黎"   -> "48.8566,2.3522";
            default                    -> "35.6762,139.6503";
        };

        return withRetry("天气查询", () -> {
            String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&timezone=Asia%%2FTokyo&forecast_days=7",
                coords.split(",")[0], coords.split(",")[1]
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root   = objectMapper.readTree(response.body());
            JsonNode daily  = root.get("daily");
            JsonNode dates  = daily.get("time");
            JsonNode maxT   = daily.get("temperature_2m_max");
            JsonNode minT   = daily.get("temperature_2m_min");
            JsonNode precip = daily.get("precipitation_sum");

            StringBuilder sb = new StringBuilder(city + " 未来3天天气：\n");
            for (int i = 0; i < 3; i++) {
                sb.append(String.format("  %s：%s～%s°C，降水%.1fmm\n",
                    dates.get(i).asText(),
                    minT.get(i).asText(),
                    maxT.get(i).asText(),
                    precip.get(i).asDouble()
                ));
            }
            return sb.toString();

        }, null);   // 天气无合适缓存，失败返回错误说明
    }

    // ─────────────────────────────────────────────────────────
    // 工具 3：航班搜索（Mock，无需重试）
    // ─────────────────────────────────────────────────────────
    public String searchFlights(String origin, String destination, String date) {
        String originCode = toAirportCode(origin);
        String destCode   = toAirportCode(destination);

        return String.format(
            "%s → %s 航班搜索结果（%s）：\n" +
            "  🛫 MH612  出发08:30 抵达16:45  价格：¥2,380  马来西亚航空\n" +
            "  🛫 JL729  出发10:15 抵达18:30  价格：¥2,950  日本航空\n" +
            "  🛫 SQ637  出发13:00 抵达21:10  价格：¥2,650  新加坡航空\n" +
            "  🛫 AK1234 出发06:00 抵达14:15  价格：¥1,850  亚航（经停吉隆坡）",
            originCode, destCode, date
        );
    }

    // ─────────────────────────────────────────────────────────
    // 工具 4：费用计算（纯计算，无需重试）
    // ─────────────────────────────────────────────────────────
    public String calculateTripCost(double flightPrice, double hotelPerNight,
                                     int nights, String currency) {
        double total = flightPrice + hotelPerNight * nights;
        return String.format(
            "旅行费用估算（%s）：\n机票：%.0f\n酒店：%.0f × %d晚 = %.0f\n合计：%.0f",
            currency, flightPrice, hotelPerNight, nights, hotelPerNight * nights, total
        );
    }

    private String toAirportCode(String city) {
        return switch (city.toLowerCase()) {
            case "singapore", "新加坡", "sin" -> "SIN";
            case "tokyo",     "东京",   "nrt" -> "NRT";
            case "osaka",     "大阪",   "kix" -> "KIX";
            case "bangkok",   "曼谷",   "bkk" -> "BKK";
            case "seoul",     "首尔",   "icn" -> "ICN";
            case "hong kong", "香港",   "hkg" -> "HKG";
            case "beijing",   "北京",   "pek" -> "PEK";
            case "shanghai",  "上海",   "pvg" -> "PVG";
            default -> city.toUpperCase().substring(0, Math.min(3, city.length()));
        };
    }
}
