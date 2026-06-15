/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM SQL 生成器
 * <p>
 * 调用 OpenAI 兼容的 LLM API（如 mimo-v2-pro）将自然语言转换为 PostgreSQL SELECT 语句。
 * 使用 Java 21 内置的 HttpClient，无需额外依赖。
 */
@Slf4j
public final class LlmSqlGenerator {

    private LlmSqlGenerator() {
    }

    private static final String SYSTEM_PROMPT = """
            你是一个专业的 PostgreSQL SQL 生成器。根据用户提供的数据库表结构和自然语言问题，生成正确的 PostgreSQL SELECT 查询语句。

            规则：
            1. 只生成 SELECT 语句，禁止生成 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE 等语句
            2. 使用 PostgreSQL 语法，包括 LIMIT 而非 TOP
            3. 优先使用表别名和明确的列名，避免 SELECT *
            4. 对于中文字段值，使用精确匹配（如 status = '成功'）
            5. 对于日期查询，使用 PostgreSQL 日期函数（如 CURRENT_DATE, INTERVAL '7 days'）
            6. 查询结果限制在 200 行以内，使用 LIMIT 子句
            7. 所有表均有 deleted 字段 (0=正常, 1=已删除), 查询时必须添加 WHERE deleted = 0
            8. 只输出 SQL 语句，不要输出任何解释、注释或 markdown 格式
            """;

    /**
     * 调用 LLM 生成 SQL
     *
     * @param httpClient    HTTP 客户端
     * @param apiUrl        LLM API 地址
     * @param apiKey        API Key
     * @param model         模型名称
     * @param schemaContext 数据库 schema 上下文
     * @param question      用户自然语言问题
     * @param timeoutSeconds 超时时间（秒）
     * @return 生成的 SQL 语句
     * @throws Exception 调用失败时抛出
     */
    public static String generateSql(HttpClient httpClient, String apiUrl, String apiKey,
                                     String model, String schemaContext, String question,
                                     int timeoutSeconds) throws Exception {

        // 构建用户消息：schema 上下文 + 问题
        String userMessage = "数据库表结构:\n" + schemaContext + "\n\n将以下自然语言问题转换为 SQL:\n" + question;

        // 手动构建 OpenAI 兼容的 JSON 请求体（避免引入 Gson/Jackson 依赖）
        String requestBody = buildRequestBody(model, userMessage, timeoutSeconds);

        log.info("NL2SQL LLM 调用开始, model={}, question={}", model, truncate(question, 100));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("NL2SQL LLM 调用失败, status={}, body={}", response.statusCode(), truncate(response.body(), 500));
            throw new RuntimeException("LLM API 调用失败, HTTP " + response.statusCode());
        }

        log.info("NL2SQL LLM 原始响应: {}", truncate(response.body(), 1000));
        String sql = parseResponse(response.body());
        log.info("NL2SQL LLM 生成 SQL: {}", truncate(sql, 200));
        return sql;
    }

    /**
     * 构建 OpenAI 兼容的请求体 JSON
     */
    private static String buildRequestBody(String model, String userMessage, int timeoutSeconds) {
        // 转义 JSON 字符串中的特殊字符
        String escapedSystem = escapeJson(SYSTEM_PROMPT);
        String escapedUser = escapeJson(userMessage);

        return "{" +
                "\"model\":\"" + escapeJson(model) + "\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"" + escapedSystem + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + escapedUser + "\"}]," +
                "\"temperature\":0.1," +
                "\"top_p\":0.3," +
                "\"max_tokens\":1024" +
                "}";
    }

    /**
     * 解析 LLM 响应，提取 SQL
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String parseResponse(String responseBody) throws Exception {
        // 使用 Jackson 正确解析 OpenAI 兼容的 JSON 响应
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM 响应中未找到 choices: " + truncate(responseBody, 300));
        }

        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("LLM 响应中未找到 message: " + truncate(responseBody, 300));
        }

        JsonNode contentNode = message.get("content");
        if (contentNode == null || contentNode.isNull()) {
            throw new RuntimeException("LLM 响应 content 为空: " + truncate(responseBody, 300));
        }

        String content = contentNode.asText();
        log.info("NL2SQL parseResponse: content={}", truncate(content, 200));

        // 清理 markdown 代码块
        content = stripMarkdownCodeFence(content);

        // 去除末尾分号
        content = content.trim();
        if (content.endsWith(";")) {
            content = content.substring(0, content.length() - 1).trim();
        }

        // 验证是 SELECT 语句
        String upper = content.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new RuntimeException("LLM 未生成有效的 SELECT 语句: " + truncate(content, 200));
        }

        return content;
    }

    /**
     * 去除 markdown 代码块围栏
     */
    private static String stripMarkdownCodeFence(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```sql")) {
            trimmed = trimmed.substring(6);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /**
     * 转义 JSON 字符串特殊字符
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 截断字符串用于日志
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
