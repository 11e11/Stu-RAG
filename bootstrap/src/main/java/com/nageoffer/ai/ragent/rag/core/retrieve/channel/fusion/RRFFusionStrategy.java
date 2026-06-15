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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF (Reciprocal Rank Fusion) 融合策略
 * <p>
 * RRF 是一种简单有效的排序融合方法，公式如下：
 * RRF_score(d) = sum(1 / (k + rank_i(d)))
 * <p>
 * 其中 k 是平滑参数（通常为 60），rank_i(d) 是文档 d 在第 i 个排序列表中的排名
 * <p>
 * 参考论文: Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods (2009)
 */
@Slf4j
@Component
public class RRFFusionStrategy implements FusionStrategy {

    /**
     * RRF 平滑参数 k
     * k 值越大，排名靠后的文档得分差异越小
     * 常用值为 60
     */
    private static final int K = 60;

    @Override
    public String getName() {
        return "RRF";
    }

    @Override
    public List<RetrievedChunk> fuse(List<List<RetrievedChunk>> results, int topK) {
        // key: chunk id 或内容哈希, value: (chunk, rrf_score)
        Map<String, ScoredChunk> chunkScoreMap = new LinkedHashMap<>();

        for (List<RetrievedChunk> channelResults : results) {
            int rank = 1;
            for (RetrievedChunk chunk : channelResults) {
                String key = generateChunkKey(chunk);
                double rrfScore = 1.0 / (K + rank);

                chunkScoreMap.merge(key,
                        new ScoredChunk(chunk, rrfScore),
                        (existing, ignored) -> {
                            existing.addScore(rrfScore);
                            return existing;
                        }
                );
                rank++;
            }
        }

        // 按 RRF 分数降序排序，取 topK
        List<ScoredChunk> sorted = chunkScoreMap.values().stream()
                .sorted((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()))
                .limit(topK)
                .toList();

        log.info("RRF 融合完成 - 输入 {} 个通道，合并后 {} 个唯一 Chunk，输出 Top-{}",
                results.size(), chunkScoreMap.size(), topK);

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
        private double rrfScore;

        ScoredChunk(RetrievedChunk chunk, double initialScore) {
            this.chunk = chunk;
            this.rrfScore = initialScore;
        }

        void addScore(double score) {
            this.rrfScore += score;
        }

        double getRrfScore() {
            return rrfScore;
        }

        RetrievedChunk getChunk() {
            return chunk;
        }
    }
}
