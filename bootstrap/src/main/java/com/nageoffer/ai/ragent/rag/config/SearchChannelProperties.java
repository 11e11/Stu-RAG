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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 默认返回的 TopK
     */
    private int defaultTopK = 10;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();

        /**
         * 关键词检索配置（PG tsquery）
         */
        private Keyword keyword = new Keyword();

        /**
         * 混合检索配置
         */
        private Hybrid hybrid = new Hybrid();
    }

    /**
     * 融合模式枚举
     */
    public enum FusionMode {
        /**
         * Reciprocal Rank Fusion
         */
        RRF,
        /**
         * 加权求和
         */
        WEIGHTED_SUM
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 3;
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class Keyword {

        /**
         * 是否启用关键词检索通道
         */
        private boolean enabled = true;

        /**
         * TopK 倍数
         * 关键词检索时召回更多候选，后续通过融合排序筛选
         */
        private int topKMultiplier = 3;

        /**
         * 分数提升权重
         * 关键词检索结果的分数乘以此系数
         */
        private double boost = 1.0;
    }

    @Data
    public static class Hybrid {

        /**
         * 是否启用混合融合后处理器
         */
        private boolean enabled = true;

        /**
         * 融合模式 (RRF / WEIGHTED_SUM)
         */
        private FusionMode fusion = FusionMode.RRF;

        /**
         * 向量通道权重（仅 WEIGHTED_SUM 模式生效）
         * 关键词通道权重 = 1 - vectorWeight
         */
        private double vectorWeight = 0.7;
    }
}
