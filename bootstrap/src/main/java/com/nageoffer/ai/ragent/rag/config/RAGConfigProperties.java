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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code false}
     */
    @Value("${rag.query-rewrite.enabled:false}")
    private Boolean queryRewriteEnabled;

    /**
     * 意图识别功能开关
     * <p>
     * 控制是否启用意图识别功能，意图识别可以将用户问题分类到不同的知识库或工具
     * 关闭后将跳过意图识别，直接进行全局检索
     * 默认值：{@code false}
     */
    @Value("${rag.intent.enabled:false}")
    private Boolean intentEnabled;

    /**
     * Rerank 重排序功能开关
     * <p>
     * 控制是否启用 Rerank 后置处理器对召回结果进行重排序
     * 默认值：{@code false}
     */
    @Value("${rag.rerank.enabled:false}")
    private Boolean rerankEnabled;
}
