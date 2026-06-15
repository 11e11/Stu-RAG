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

import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 * <p>
 * 确保 LLM 生成的 SQL 仅包含只读查询（SELECT），防止数据篡改或泄露。
 */
public final class SqlSafetyValidator {

    private SqlSafetyValidator() {
    }

    /**
     * 危险关键词（大小写不敏感，单词边界匹配）
     */
    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|EXEC|EXECUTE|CALL|DO|COPY)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * SQL 注释（单行 -- 和多行 block comment）
     */
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("--[^\n]*");
    private static final Pattern MULTI_LINE_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    /**
     * 分号检测（禁止多语句）
     */
    private static final Pattern SEMICOLON = Pattern.compile(";");

    /**
     * 校验 SQL 安全性
     *
     * @param sql 待校验的 SQL 语句
     * @throws SqlSafetyException 如果 SQL 不安全
     */
    public static void validate(String sql) throws SqlSafetyException {
        if (sql == null || sql.isBlank()) {
            throw new SqlSafetyException("SQL 语句不能为空");
        }

        // 1. 去除注释
        String cleaned = SINGLE_LINE_COMMENT.matcher(sql).replaceAll("");
        cleaned = MULTI_LINE_COMMENT.matcher(cleaned).replaceAll("");
        cleaned = cleaned.trim();

        // 2. 去除末尾分号（LLM 经常生成带分号的 SQL）
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        // 3. 禁止分号（防止多语句注入，如 SELECT 1; DROP TABLE）
        if (SEMICOLON.matcher(cleaned).find()) {
            throw new SqlSafetyException("禁止执行多条 SQL 语句");
        }

        // 4. 必须以 SELECT 或 WITH（CTE）开头
        String upper = cleaned.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new SqlSafetyException("仅支持 SELECT 查询语句");
        }

        // 5. 检查危险关键词
        if (DANGEROUS_KEYWORDS.matcher(upper).find()) {
            throw new SqlSafetyException("SQL 包含禁止的写操作关键词");
        }
    }

    /**
     * SQL 安全异常
     */
    public static class SqlSafetyException extends Exception {
        public SqlSafetyException(String message) {
            super(message);
        }
    }
}
