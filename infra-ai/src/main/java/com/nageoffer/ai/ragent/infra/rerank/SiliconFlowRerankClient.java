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

package com.nageoffer.ai.ragent.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SiliconFlow Rerank 客户端
 * 使用 Jina AI 兼容的 /v1/rerank 协议
 */
@Service
@Slf4j
public class SiliconFlowRerankClient implements RerankClient {

    private final OkHttpClient httpClient;

    public SiliconFlowRerankClient(@Qualifier("syncHttpClient") OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 去重
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig providerConfig = HttpResponseHelper.requireProvider(target, provider());

        // 构建 SiliconFlow / Jina 兼容格式的请求体
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        reqBody.addProperty("query", query);
        reqBody.addProperty("top_n", topN);
        reqBody.addProperty("return_documents", false);

        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        reqBody.add("documents", documentsArray);

        String url = ModelUrlResolver.resolveUrl(providerConfig, target.candidate(), ModelCapability.RERANK);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + providerConfig.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 解析 SiliconFlow 响应格式：{ "results": [{ "index": 0, "relevance_score": 0.95 }] }
        JsonArray results = respJson.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            RetrievedChunk src = candidates.get(idx);

            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            RetrievedChunk hit = score != null ? new RetrievedChunk(src.getId(), src.getText(), score) : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            if (reranked.size() >= topN) {
                break;
            }
        }

        // 兜底：如果 rerank 结果不够 topN，用原始顺序补
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }
}
