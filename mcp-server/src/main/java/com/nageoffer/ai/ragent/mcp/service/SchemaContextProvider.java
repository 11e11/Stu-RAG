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

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据库 Schema 上下文提供器
 * <p>
 * 通过查询 PostgreSQL information_schema 构建 DDL 风格的表结构描述，
 * 供 LLM 生成 SQL 时作为上下文参考。
 * <p>
 * 结果缓存 5 分钟，避免每次查询都扫描系统表。
 */
@Slf4j
public final class SchemaContextProvider {

    private SchemaContextProvider() {
    }

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static volatile String cachedSchema;
    private static final AtomicLong cacheExpiry = new AtomicLong(0);

    /**
     * 获取数据库 schema 上下文（带缓存）
     *
     * @param jdbc JdbcTemplate
     * @return DDL 风格的 schema 描述
     */
    public static String getSchemaContext(JdbcTemplate jdbc) {
        long now = System.currentTimeMillis();
        String schema = cachedSchema;
        if (schema != null && now < cacheExpiry.get()) {
            return schema;
        }

        synchronized (SchemaContextProvider.class) {
            schema = cachedSchema;
            if (schema != null && System.currentTimeMillis() < cacheExpiry.get()) {
                return schema;
            }
            schema = buildSchemaContext(jdbc);
            cachedSchema = schema;
            cacheExpiry.set(System.currentTimeMillis() + CACHE_TTL_MS);
            log.info("Schema 上下文已刷新, 长度={}字符", schema.length());
            return schema;
        }
    }

    private static String buildSchemaContext(JdbcTemplate jdbc) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库: PostgreSQL (ragent)\n\n");

        // 获取所有用户表
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT table_name, obj_description((table_schema || '.' || table_name)::regclass) AS table_comment " +
                "FROM information_schema.tables t " +
                "LEFT JOIN pg_class c ON c.relname = t.table_name " +
                "WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE' " +
                "ORDER BY t.table_name"
        );

        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("table_name");
            String tableComment = (String) table.get("table_comment");

            // 表注释
            sb.append("-- Table: ").append(tableName);
            if (tableComment != null && !tableComment.isBlank()) {
                sb.append(" (").append(tableComment).append(")");
            }
            sb.append("\n");

            // 获取列信息
            List<Map<String, Object>> columns = jdbc.queryForList(
                    "SELECT c.column_name, c.data_type, c.character_maximum_length, " +
                    "       c.is_nullable, c.column_default, " +
                    "       col_description((table_schema || '.' || table_name)::regclass::oid, c.ordinal_position) AS column_comment " +
                    "FROM information_schema.columns c " +
                    "WHERE c.table_schema = 'public' AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position",
                    tableName
            );

            sb.append("CREATE TABLE ").append(tableName).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = columns.get(i);
                String colName = (String) col.get("column_name");
                String dataType = (String) col.get("data_type");
                Number maxLength = (Number) col.get("character_maximum_length");
                String nullable = (String) col.get("is_nullable");
                String comment = (String) col.get("column_comment");

                sb.append("    ").append(colName).append(" ").append(dataType);
                if (maxLength != null && maxLength.intValue() > 0) {
                    sb.append("(").append(maxLength.intValue()).append(")");
                }
                if ("NO".equals(nullable)) {
                    sb.append(" NOT NULL");
                }
                if (comment != null && !comment.isBlank()) {
                    sb.append(",  -- ").append(comment);
                } else {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(");\n\n");
        }

        // 获取外键关系
        List<Map<String, Object>> fks = jdbc.queryForList(
                "SELECT tc.table_name, kcu.column_name, ccu.table_name AS foreign_table_name, " +
                "       ccu.column_name AS foreign_column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                "JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'"
        );

        if (!fks.isEmpty()) {
            sb.append("-- 外键关系:\n");
            for (Map<String, Object> fk : fks) {
                sb.append("-- ")
                  .append(fk.get("table_name")).append(".").append(fk.get("column_name"))
                  .append(" -> ")
                  .append(fk.get("foreign_table_name")).append(".").append(fk.get("foreign_column_name"))
                  .append("\n");
            }
            sb.append("\n");
        }

        // 注意事项
        sb.append("-- 注意: 所有表均有 deleted 字段 (0=正常, 1=已删除), 查询时应添加 WHERE deleted = 0\n");

        return sb.toString();
    }
}
