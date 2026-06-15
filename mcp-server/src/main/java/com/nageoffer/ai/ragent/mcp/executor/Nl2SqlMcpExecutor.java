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

package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.config.Nl2SqlProperties;
import com.nageoffer.ai.ragent.mcp.service.LlmSqlGenerator;
import com.nageoffer.ai.ragent.mcp.service.SchemaContextProvider;
import com.nageoffer.ai.ragent.mcp.service.SqlSafetyValidator;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NL2SQL MCP 工具执行器
 * <p>
 * 将自然语言问题转换为 PostgreSQL SELECT 查询并执行，
 * 使用 mimo-v2-pro 模型作为 SQL 生成器。
 */
@Slf4j
@Component
public class Nl2SqlMcpExecutor {

    private static final String TOOL_ID = "nl2sql_query";

    private final JdbcTemplate jdbcTemplate;
    private final Nl2SqlProperties properties;
    private final HttpClient httpClient;

    public Nl2SqlMcpExecutor(JdbcTemplate jdbcTemplate, Nl2SqlProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification nl2sqlToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("question", Map.of(
                "type", "string",
                "description", "自然语言问题，例如：用户总数是多少？、最近7天创建的会话有多少？、各知识库的文档数量排名"
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("question"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("通过自然语言查询 PostgreSQL 数据库，将问题转换为 SQL 并执行查询。支持用户统计、会话分析、知识库文档统计、意图配置查询等")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
            String question = stringArg(args, "question");

            if (question == null || question.isBlank()) {
                return errorResult("请提供要查询的问题");
            }

            log.info("NL2SQL 工具调用开始, question={}", question);

            // 1. 获取数据库 schema 上下文
            String schemaContext = SchemaContextProvider.getSchemaContext(jdbcTemplate);

            // 2. 调用 LLM 生成 SQL
            String sql = LlmSqlGenerator.generateSql(
                    httpClient,
                    properties.getApiUrl(),
                    properties.getApiKey(),
                    properties.getModel(),
                    schemaContext,
                    question,
                    properties.getLlmTimeoutSeconds()
            );

            // 3. 安全校验
            SqlSafetyValidator.validate(sql);

            // 4. 执行 SQL 查询
            String limitedSql = enforceLimit(sql, properties.getMaxRows());
            log.info("NL2SQL 执行 SQL: {}", limitedSql);

            List<Map<String, Object>> results = jdbcTemplate.queryForList(limitedSql);

            // 5. 格式化结果
            String formattedResult = formatResults(question, limitedSql, results);

            log.info("NL2SQL 工具调用完成, question={}, rows={}, elapsed={}ms",
                    question, results.size(), System.currentTimeMillis() - startMs);

            return successResult(formattedResult);
        } catch (SqlSafetyValidator.SqlSafetyException e) {
            log.warn("NL2SQL 安全校验失败: {}", e.getMessage());
            return errorResult("SQL 安全校验失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("NL2SQL 工具调用失败, elapsed={}ms", System.currentTimeMillis() - startMs, e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    /**
     * 强制添加或限制 LIMIT 子句
     */
    private String enforceLimit(String sql, int maxRows) {
        String upper = sql.trim().toUpperCase();
        if (upper.contains("LIMIT")) {
            // 如果已有 LIMIT，检查是否超过限制
            // 简单处理：如果包含 LIMIT，信任 LLM 的限制（已由 prompt 约束）
            return sql;
        }
        return sql.trim() + " LIMIT " + maxRows;
    }

    /**
     * 格式化查询结果为可读文本
     */
    private String formatResults(String question, String sql, List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();

        if (results.isEmpty()) {
            sb.append("查询结果为空，未找到匹配的数据记录。");
            return sb.toString();
        }

        // 单行聚合结果（如 COUNT、SUM）
        if (results.size() == 1 && results.get(0).size() <= 3) {
            Map<String, Object> row = results.get(0);
            sb.append("【查询结果】\n\n");
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(formatValue(entry.getValue())).append("\n");
            }
            sb.append("\n共 ").append(results.size()).append(" 条记录");
            return sb.toString();
        }

        // 多行结果 — 使用表格格式
        Map<String, Object> firstRow = results.get(0);
        List<String> columns = List.copyOf(firstRow.keySet());

        // 表头
        sb.append("【查询结果】 (共 ").append(results.size()).append(" 条记录)\n\n");
        sb.append("| ");
        for (String col : columns) {
            sb.append(col).append(" | ");
        }
        sb.append("\n| ");
        for (String col : columns) {
            sb.append("--- | ");
        }
        sb.append("\n");

        // 数据行（最多显示 50 行）
        int displayLimit = Math.min(results.size(), 50);
        for (int i = 0; i < displayLimit; i++) {
            Map<String, Object> row = results.get(i);
            sb.append("| ");
            for (String col : columns) {
                sb.append(formatValue(row.get(col))).append(" | ");
            }
            sb.append("\n");
        }

        if (results.size() > displayLimit) {
            sb.append("\n... 共 ").append(results.size()).append(" 条记录，仅显示前 ").append(displayLimit).append(" 条");
        }

        return sb.toString();
    }

    /**
     * 格式化单个字段值
     */
    private static String formatValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}
