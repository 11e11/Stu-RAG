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

import java.util.List;

/**
 * 融合策略接口
 * <p>
 * 定义多通道检索结果的融合排序策略，例如：
 * - RRF (Reciprocal Rank Fusion)
 * - 加权求和 (Weighted Sum)
 */
public interface FusionStrategy {

    /**
     * 融合多个通道的检索结果
     *
     * @param results 各通道的检索结果列表（每个列表代表一个通道的返回结果）
     * @param topK    期望返回的结果数量
     * @return 融合排序后的 Chunk 列表
     */
    List<RetrievedChunk> fuse(List<List<RetrievedChunk>> results, int topK);

    /**
     * 策略名称
     */
    String getName();
}
