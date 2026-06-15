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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

/**
 * PG tsquery 关键词检索通道
 * <p>
 * 使用 PostgreSQL 全文检索功能，依赖 zhparser 扩展进行中文分词。
 * 通过 tsvector 和 tsquery 实现高效的关键词匹配。
 * <p>
 * 前置条件：
 * 1. 数据库已安装 zhparser 扩展
 * 2. t_knowledge_vector 表已添加 tsv 列和 GIN 索引
 * 3. 已创建自动维护 tsv 列的 trigger
 */
@Slf4j
@Component
public class KeywordSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KeywordSearchChannel(SearchChannelProperties properties,
                                 JdbcTemplate jdbcTemplate,
                                 KnowledgeBaseMapper knowledgeBaseMapper) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public String getName() {
        return "KeywordSearch";
    }

    @Override
    public int getPriority() {
        return 5;  // 中等优先级（介于意图定向和全局检索之间）
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeyword().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            String query = context.getMainQuestion();
            int topK = context.getTopK() * properties.getChannels().getKeyword().getTopKMultiplier();
            double boost = properties.getChannels().getKeyword().getBoost();

            log.info("执行关键词检索，问题：{}, topK: {}, boost: {}", query, topK, boost);

            // 获取所有 KB 类型的 collection
            List<String> collections = getAllKBCollections();

            if (collections.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过关键词检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.KEYWORD_ES)
                        .channelName(getName())
                        .chunks(List.of())
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // 在所有 collection 中执行关键词检索
            List<RetrievedChunk> allChunks = searchFromAllCollections(query, collections, topK, boost);

            long latency = System.currentTimeMillis() - startTime;

            log.info("关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_ES;
    }

    /**
     * 获取所有 KB 类型的 collection
     */
    private List<String> getAllKBCollections() {
        Set<String> collections = new HashSet<>();

        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        for (KnowledgeBaseDO kb : kbList) {
            String collectionName = kb.getCollectionName();
            if (collectionName != null && !collectionName.isBlank()) {
                collections.add(collectionName);
            }
        }

        return new ArrayList<>(collections);
    }

    /**
     * 在所有 collection 中执行关键词检索
     */
    private List<RetrievedChunk> searchFromAllCollections(String query,
                                                          List<String> collections,
                                                          int topK,
                                                          double boost) {
        List<RetrievedChunk> allChunks = new ArrayList<>();

        // 对查询进行分词处理，生成 tsquery
        String tsQuery = buildTsQuery(query);

        if (tsQuery == null || tsQuery.isBlank()) {
            log.warn("查询分词后为空，跳过关键词检索");
            return allChunks;
        }

        for (String collection : collections) {
            try {
                List<RetrievedChunk> chunks = searchFromCollection(collection, tsQuery, topK, boost);
                allChunks.addAll(chunks);
            } catch (Exception e) {
                log.error("关键词检索 collection {} 失败", collection, e);
            }
        }

        // 按分数降序排序，取 topK
        allChunks.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (allChunks.size() > topK) {
            allChunks = allChunks.subList(0, topK);
        }

        return allChunks;
    }

    /**
     * 从单个 collection 中检索
     * <p>
     * 使用 tsvector 的 @@ 操作符进行全文检索
     * 使用 ts_rank_cd 计算排名分数
     */
    private List<RetrievedChunk> searchFromCollection(String collection,
                                                      String tsQuery,
                                                      int topK,
                                                      double boost) {
        // 使用中文搜索配置进行检索
        // ts_rank_cd 的第二个参数 32 表示 cover density ranking
        String sql = """
                SELECT id, content,
                       ts_rank_cd(tsv, to_tsquery('chinese', ?), 32) * ? AS score
                FROM t_knowledge_vector
                WHERE metadata->>'collection_name' = ?
                  AND tsv @@ to_tsquery('chinese', ?)
                ORDER BY ts_rank_cd(tsv, to_tsquery('chinese', ?), 32) DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                tsQuery, boost, collection, tsQuery, tsQuery, topK
        );
    }

    /**
     * 构建 tsquery
     * <p>
     * 将用户查询转换为 tsquery 格式
     * 例如：输入 "社保政策" -> 输出 "社保 & 政策"
     */
    private String buildTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        // 使用 PG 的 to_tsvector 函数进行分词
        // 然后将分词结果转换为 tsquery 格式
        try {
            // 先对查询文本进行分词
            String sql = "SELECT array_to_string(tsvector_to_array(to_tsvector('chinese', ?)), ' & ')";
            String result = jdbcTemplate.queryForObject(sql, String.class, query);

            if (result == null || result.isBlank()) {
                // 如果分词失败，尝试使用简单分词
                log.warn("zhparser 分词失败，使用简单分词作为备选");
                return buildSimpleTsQuery(query);
            }

            return result;
        } catch (Exception e) {
            log.warn("tsvector 分词失败: {}，使用简单分词作为备选", e.getMessage());
            return buildSimpleTsQuery(query);
        }
    }

    /**
     * 简单分词构建 tsquery（备选方案）
     * <p>
     * 当 zhparser 不可用时使用
     */
    private String buildSimpleTsQuery(String query) {
        // 移除标点符号，按空格分词
        String[] tokens = query.replaceAll("[\\p{Punct}]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .split("\\s+");

        if (tokens.length == 0) {
            return null;
        }

        // 使用 & 连接所有 token（AND 语义）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(" & ");
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }
}
