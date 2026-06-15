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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加权求和融合策略
 * <p>
 * 将各通道的分数乘以对应权重后相加，公式如下：
 * weighted_score(d) = sum(weight_i * normalized_score_i(d))
 * <p>
 * 其中 weight_i 是第 i 个通道的权重，normalized_score_i(d) 是文档 d 在第 i 个通道的归一化分数
 */
@Slf4j
@Component
public class WeightedSumFusionStrategy implements FusionStrategy {

    private final SearchChannelProperties properties;

    public WeightedSumFusionStrategy(SearchChannelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "WEIGHTED_SUM";
    }

    @Override
    public List<RetrievedChunk> fuse(List<List<RetrievedChunk>> results, int topK) {
        // key: chunk id 或内容哈希, value: (chunk, weighted_score)
        Map<String, ScoredChunk> chunkScoreMap = new LinkedHashMap<>();

        // 获取向量通道权重
        double vectorWeight = properties.getChannels().getHybrid().getVectorWeight();
        double keywordWeight = 1.0 - vectorWeight;

        for (int channelIdx = 0; channelIdx < results.size(); channelIdx++) {
            List<RetrievedChunk> channelResults = results.get(channelIdx);

            // 确定当前通道的权重
            double weight = (channelIdx == 0) ? vectorWeight : keywordWeight;

            // 对通道内的分数进行归一化（如果需要）
            double maxScore = channelResults.stream()
                    .mapToDouble(RetrievedChunk::getScore)
                    .max()
                    .orElse(1.0);

            for (RetrievedChunk chunk : channelResults) {
                String key = generateChunkKey(chunk);
                // 归一化分数后乘以权重
                double normalizedScore = (maxScore > 0) ? chunk.getScore() / maxScore : 0;
                double weightedScore = normalizedScore * weight;

                chunkScoreMap.merge(key,
                        new ScoredChunk(chunk, weightedScore),
                        (existing, ignored) -> {
                            existing.addScore(weightedScore);
                            return existing;
                        }
                );
            }
        }

        // 按加权分数降序排序，取 topK
        List<ScoredChunk> sorted = chunkScoreMap.values().stream()
                .sorted((a, b) -> Double.compare(b.getWeightedScore(), a.getWeightedScore()))
                .limit(topK)
                .toList();

        log.info("加权求和融合完成 - 输入 {} 个通道，向量权重={}, 关键词权重={}，合并后 {} 个唯一 Chunk，输出 Top-{}",
                results.size(), vectorWeight, keywordWeight, chunkScoreMap.size(), topK);

        return sorted.stream()
                .map(ScoredChunk::getChunk)
                .toList();
    }

    /**
     * 生成 Chunk 唯一键
     */
    private String generateChunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null
                ? chunk.getId()
                : String.valueOf(chunk.getText().hashCode());
    }

    /**
     * 带分数的 Chunk 包装类
     */
    private static class ScoredChunk {
        private final RetrievedChunk chunk;
        private double weightedScore;

        ScoredChunk(RetrievedChunk chunk, double initialScore) {
            this.chunk = chunk;
            this.weightedScore = initialScore;
        }

        void addScore(double score) {
            this.weightedScore += score;
        }

        double getWeightedScore() {
            return weightedScore;
        }

        RetrievedChunk getChunk() {
            return chunk;
        }
    }
}
