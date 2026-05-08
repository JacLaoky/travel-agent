package com.example.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// @Service 告诉 Spring：这是一个业务逻辑组件，自动管理它的生命周期
// 类比 Python：就是一个普通的 class，Spring 帮你 new 出来并注入到需要它的地方
@Service
public class ToolService {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 工具

    // ─────────────────────────────────────────────────────────
    // 工具 1：获取实时汇率
    // 调用免费 API：exchangerate-api.com（不需要 key）
    // 类比：就是 Python 的 requests.get(url).json()
    // ─────────────────────────────────────────────────────────
    public String getExchangeRate(String baseCurrency, String targetCurrency) {
        try {
            String url = "https://api.exchangerate-api.com/v4/latest/" + baseCurrency.toUpperCase();

            // Java 21 的 HttpClient，类比 Python requests
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 解析 JSON，类比 Python 的 response.json()["rates"]["JPY"]
            JsonNode root = objectMapper.readTree(response.body());
            double rate = root.get("rates").get(targetCurrency.toUpperCase()).asDouble();

            return String.format("1 %s = %.4f %s（实时汇率）",
                    baseCurrency.toUpperCase(), rate, targetCurrency.toUpperCase());

        } catch (Exception e) {
            return "汇率查询失败：" + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────
    // 工具 2：获取目的地天气预报
    // 调用免费 API：open-meteo.com（不需要 key）
    // 只需经纬度，东京：35.6762, 139.6503
    // ─────────────────────────────────────────────────────────
    public String getWeather(String city) {
        // 内置主要城市的经纬度（真实项目可以先调地理编码 API）
        String coords = switch (city.toLowerCase()) {
            case "tokyo", "东京"       -> "35.6762,139.6503";
            case "osaka", "大阪"       -> "34.6937,135.5023";
            case "bangkok", "曼谷"     -> "13.7563,100.5018";
            case "singapore", "新加坡" -> "1.3521,103.8198";
            case "seoul", "首尔"       -> "37.5665,126.9780";
            case "paris", "巴黎"       -> "48.8566,2.3522";
            default                    -> "35.6762,139.6503"; // 默认东京
        };

        try {
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

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root   = objectMapper.readTree(response.body());
            JsonNode daily  = root.get("daily");
            JsonNode dates  = daily.get("time");
            JsonNode maxT   = daily.get("temperature_2m_max");
            JsonNode minT   = daily.get("temperature_2m_min");
            JsonNode precip = daily.get("precipitation_sum");

            // 拼接未来3天天气摘要
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

        } catch (Exception e) {
            return "天气查询失败：" + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────
    // 工具 3：搜索航班（用 Amadeus 沙箱 API，有真实结构）
    // 若没有 Amadeus key，返回模拟数据（结构一样）
    // ─────────────────────────────────────────────────────────
    public String searchFlights(String origin, String destination, String date) {
        // Amadeus airport codes
        String originCode = toAirportCode(origin);
        String destCode   = toAirportCode(destination);

        // 模拟真实航班数据（结构和真实 API 一致）
        // 真实项目替换成 Amadeus API 调用即可，接口不变
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
    // 工具 4：计算总费用
    // ─────────────────────────────────────────────────────────
    public String calculateTripCost(double flightPrice, double hotelPerNight,
                                     int nights, String currency) {
        double total = flightPrice + hotelPerNight * nights;
        return String.format(
            "旅行费用估算（%s）：\n机票：%.0f\n酒店：%.0f × %d晚 = %.0f\n合计：%.0f",
            currency, flightPrice, hotelPerNight, nights,
            hotelPerNight * nights, total
        );
    }

    // ─── 辅助方法 ───
    private String toAirportCode(String city) {
        return switch (city.toLowerCase()) {
            case "singapore", "新加坡", "sin" -> "SIN";
            case "tokyo", "东京", "nrt"       -> "NRT";
            case "osaka", "大阪", "kix"       -> "KIX";
            case "bangkok", "曼谷", "bkk"     -> "BKK";
            case "seoul", "首尔", "icn"       -> "ICN";
            case "hong kong", "香港", "hkg"   -> "HKG";
            case "beijing", "北京", "pek"     -> "PEK";
            case "shanghai", "上海", "pvg"    -> "PVG";
            default -> city.toUpperCase().substring(0, Math.min(3, city.length()));
        };
    }
}
