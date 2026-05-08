# Travel Agent ✈️

AI-powered travel research assistant built with Java Spring Boot and DeepSeek API.

## What it does

Ask natural language questions about travel plans. The Agent autonomously decides which tools to call and combines results into a complete recommendation.

**Example query:**
> "我想从新加坡飞东京，6月底出发，帮我看看汇率和天气适不适合，顺便找最便宜的航班"

**Agent will automatically:**
1. Query real-time SGD → JPY exchange rate
2. Check Tokyo weather forecast
3. Search available flights with prices
4. Calculate total trip cost
5. Return a comprehensive travel report

## Tech Stack

| Layer | Tech |
|-------|------|
| Backend | Java 21 · Spring Boot 3 · Maven |
| AI | DeepSeek API (OpenAI-compatible Function Calling) |
| Tools | ExchangeRate API · Open-Meteo Weather API · Flight Search |

## Architecture

```
POST /api/agent/chat
        ↓
AgentController (REST layer)
        ↓
AgentService (ReAct loop)
  ├── Call DeepSeek with tool definitions
  ├── If tool_calls → execute tool → append result → repeat
  └── If stop → return final answer
        ↓
ToolService (tool implementations)
  ├── get_exchange_rate  → exchangerate-api.com (free, no key)
  ├── get_weather        → open-meteo.com (free, no key)
  ├── search_flights     → mock data (Amadeus sandbox ready)
  └── calculate_trip_cost → pure Java logic
```

## Setup

**1. Clone and configure**
```bash
git clone https://github.com/YOUR_USERNAME/travel-agent.git
cd travel-agent
```

Create `src/main/resources/application-local.properties`:
```properties
deepseek.api.key=your-deepseek-api-key
```

**2. Run**
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Server starts on `http://localhost:8080`

## API

### Chat with Agent
```
POST /api/agent/chat
Content-Type: application/json

{
  "message": "我想从新加坡飞东京，帮我查汇率和航班"
}
```

Response:
```json
{
  "answer": "综合分析报告...",
  "toolCallCount": 3
}
```

### Health Check
```
GET /api/agent/health
```

## Key Concepts

**ReAct Agent Loop** — The agent doesn't just answer once. It thinks, calls tools, observes results, and loops until it has enough information:

```
Thought: I need exchange rate and weather data
Action: call get_exchange_rate(SGD, JPY)
Observation: 1 SGD = 123.52 JPY
Action: call get_weather(Tokyo)
Observation: 22-28°C, rainy season
Action: call search_flights(Singapore, Tokyo, 2025-06-25)
Observation: cheapest AK1234 ¥1,850
→ Final Answer: comprehensive report
```

**Function Calling** — Tools are defined as JSON schemas. DeepSeek decides when and how to call them based on the user's question.
