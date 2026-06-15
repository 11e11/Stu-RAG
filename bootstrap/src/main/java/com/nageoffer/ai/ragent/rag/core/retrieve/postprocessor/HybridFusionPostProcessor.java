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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties.FusionMode;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.FusionStrategy;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.RRFFusionStrategy;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion.WeightedSumFusionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合融合后置处理器
 * <p>
 * 对多通道检索结果进行融合排序，支持两种融合策略：
 * - RRF (Reciprocal Rank Fusion): 基于排名的融合
 * - WEIGHTED_SUM: 基于分数加权的融合
 * <p>
 * 执行顺序：在去重之后、Rerank 之前
 */
@Slf4j
@Component
public class HybridFusionPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;
    private final RRFFusionStrategy rrfFusionStrategy;
    private final WeightedSumFusionStrategy weightedSumFusionStrategy;

    public HybridFusionPostProcessor(SearchChannelProperties properties,
                                      RRFFusionStrategy rrfFusionStrategy,
                                      WeightedSumFusionStrategy weightedSumFusionStrategy) {
        this.properties = properties;
        this.rrfFusionStrategy = rrfFusionStrategy;
        this.weightedSumFusionStrategy = weightedSumFusionStrategy;
    }

    @Override
    public String getName() {
        return "HybridFusion";
    }

    @Override
    public int getOrder() {
        return 5;  // 在去重(1)之后、Rerank(10)之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getHybrid().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 如果只有一个通道有结果，不需要融合
        long channelCount = results.stream()
                .filter(r -> !r.getChunks().isEmpty())
                .count();
        if (channelCount <= 1) {
            log.info("只有 {} 个通道有结果，跳过融合", channelCount);
            return chunks;
        }

        // 获取融合策略
        FusionMode fusionMode = properties.getChannels().getHybrid().getFusion();
        FusionStrategy fusionStrategy = getFusionStrategy(fusionMode);

        log.info("使用 {} 融合策略处理 {} 个通道的结果",
                fusionMode, channelCount);

        // 将各通道的结果转换为 List<List<RetrievedChunk>>
        List<List<RetrievedChunk>> channelResults = results.stream()
                .filter(r -> !r.getChunks().isEmpty())
                .map(SearchChannelResult::getChunks)
                .toList();

        // 执行融合
        List<RetrievedChunk> fusedChunks = fusionStrategy.fuse(channelResults, context.getTopK());

        log.info("融合完成 - 输入 {} 个 Chunk，输出 {} 个 Chunk",
                chunks.size(), fusedChunks.size());

        return fusedChunks;
    }

    /**
     * 根据融合模式获取对应的融合策略
     */
    private FusionStrategy getFusionStrategy(FusionMode mode) {
        return switch (mode) {
            case RRF -> rrfFusionStrategy;
            case WEIGHTED_SUM -> weightedSumFusionStrategy;
        };
    }
}
