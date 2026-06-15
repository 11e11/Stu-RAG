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

package com.nageoffer.ai.ragent.rag.core.llm;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Spring AI 的 LLM 服务实现
 * <p>
 * 使用 Spring AI 的 ChatClient 进行模型调用，同时保留原有的熔断器和路由逻辑
 */
@Slf4j
@Service
@Primary
public class SpringAILLMService implements LLMService {

    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final AIModelProperties properties;
    private final ChatModel defaultChatModel;

    public SpringAILLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            AIModelProperties properties,
            @Qualifier("openAiChatModel") ChatModel defaultChatModel) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.properties = properties;
        this.defaultChatModel = defaultChatModel;
    }

    @Override
    @RagTraceNode(name = "llm-chat-springai", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        List<ModelTarget> targets = selector.selectChatCandidates(
                Boolean.TRUE.equals(request.getThinking())
        );

        log.info("选中的Chat模型候选数: {}", targets != null ? targets.size() : 0);
        if (targets != null) {
            targets.forEach(t -> log.info("  候选模型: id={}, provider={}, model={}",
                    t.id(), t.candidate().getProvider(), t.candidate().getModel()));
        }

        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("无可用 Chat 模型候选");
        }

        Throwable lastError = null;
        for (ModelTarget target : targets) {
            if (!healthStore.allowCall(target.id())) {
                log.info("模型 {} 被熔断，跳过", target.id());
                continue;
            }

            try {
                log.info("▶ 使用 Spring AI 调用模型: modelId={}, provider={}, model={}",
                        target.id(), target.candidate().getProvider(), target.candidate().getModel());

                ChatClient chatClient = buildChatClient(target);
                String response = doChat(chatClient, request);

                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                lastError = e;
                healthStore.markFailure(target.id());
                log.warn("模型调用失败，尝试下一个: modelId={}, provider={}",
                        target.id(), target.candidate().getProvider(), e);
            }
        }

        throw new RemoteException(
                "所有 Chat 模型调用失败: " + (lastError == null ? "unknown" : lastError.getMessage()),
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
    }

    @Override
    @RagTraceNode(name = "llm-stream-springai", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(
                Boolean.TRUE.equals(request.getThinking())
        );

        log.info("流式调用选中的Chat模型候选数: {}", targets != null ? targets.size() : 0);
        if (targets != null) {
            targets.forEach(t -> log.info("  候选模型: id={}, provider={}, model={}",
                    t.id(), t.candidate().getProvider(), t.candidate().getModel()));
        }

        if (targets == null || targets.isEmpty()) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        AtomicBoolean started = new AtomicBoolean(false);
        CountDownLatch firstPacketLatch = new CountDownLatch(1);
        List<String> receivedContent = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamCallback probeCallback = new StreamCallback() {
            @Override
            public void onContent(String content) {
                receivedContent.add(content);
                if (started.compareAndSet(false, true)) {
                    firstPacketLatch.countDown();
                }
                callback.onContent(content);
            }

            @Override
            public void onThinking(String content) {
                callback.onThinking(content);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                if (started.compareAndSet(false, true)) {
                    firstPacketLatch.countDown();
                }
                callback.onError(error);
            }
        };

        for (ModelTarget target : targets) {
            if (!healthStore.allowCall(target.id())) {
                log.info("模型 {} 被熔断，跳过", target.id());
                continue;
            }

            try {
                log.info("▶ 使用 Spring AI 流式调用模型: modelId={}, provider={}, model={}",
                        target.id(), target.candidate().getProvider(), target.candidate().getModel());

                ChatClient chatClient = buildChatClient(target);
                AtomicBoolean completed = new AtomicBoolean(false);

                // 异步执行流式调用
                Thread.startVirtualThread(() -> {
                    try {
                        doStreamChat(chatClient, request, probeCallback);
                        completed.set(true);
                        if (started.compareAndSet(false, true)) {
                            firstPacketLatch.countDown();
                        }
                    } catch (Exception e) {
                        errorRef.set(e);
                        if (started.compareAndSet(false, true)) {
                            firstPacketLatch.countDown();
                        }
                    }
                });

                // 等待首包
                boolean gotFirstPacket = firstPacketLatch.await(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (gotFirstPacket && errorRef.get() == null && !receivedContent.isEmpty()) {
                    healthStore.markSuccess(target.id());
                    return () -> {
                        // 取消句柄 - 流式调用已经完成或正在执行
                        log.info("流式调用取消请求: modelId={}", target.id());
                    };
                }

                // 失败处理
                healthStore.markFailure(target.id());
                Throwable error = errorRef.get();
                if (error != null) {
                    log.warn("模型流式调用失败: modelId={}, provider={}, error={}",
                            target.id(), target.candidate().getProvider(), error.getMessage());
                } else if (!gotFirstPacket) {
                    log.warn("模型流式调用超时: modelId={}, provider={}",
                            target.id(), target.candidate().getProvider());
                } else if (receivedContent.isEmpty()) {
                    log.warn("模型流式调用无内容: modelId={}, provider={}",
                            target.id(), target.candidate().getProvider());
                }

                // 重置状态，尝试下一个模型
                started.set(false);
                receivedContent.clear();
                errorRef.set(null);

            } catch (Exception e) {
                healthStore.markFailure(target.id());
                log.warn("模型流式调用启动失败: modelId={}, provider={}",
                        target.id(), target.candidate().getProvider(), e);
            }
        }

        throw new RemoteException(STREAM_ALL_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
    }

    private ChatClient buildChatClient(ModelTarget target) {
        // 根据 provider 信息动态创建 ChatClient
        AIModelProperties.ProviderConfig providerConfig = properties.getProviders()
                .get(target.candidate().getProvider());

        if (providerConfig == null) {
            log.warn("Provider配置缺失: provider={}, 使用默认ChatClient", target.candidate().getProvider());
            return ChatClient.create(defaultChatModel);
        }

        // 构建 OpenAI API 客户端
        String baseUrl = providerConfig.getUrl();
        String apiKey = providerConfig.getApiKey();

        // 获取 chat endpoint（作为相对路径）
        String chatEndpoint = providerConfig.getEndpoints().get("chat");
        if (chatEndpoint == null) {
            chatEndpoint = "/v1/chat/completions";
        }

        log.info("构建ChatClient: baseUrl={}, completionsPath={}, model={}",
                baseUrl, chatEndpoint, target.candidate().getModel());

        // 创建 OpenAiApi
        // completionsPath 是相对于 baseUrl 的路径
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(chatEndpoint)
                .build();

        // 创建 ChatModel
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(target.candidate().getModel())
                        .build())
                .build();

        return ChatClient.create(chatModel);
    }

    private String doChat(ChatClient chatClient, ChatRequest request) {
        Prompt prompt = buildPrompt(request);
        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    private void doStreamChat(ChatClient chatClient, ChatRequest request, StreamCallback callback) {
        Prompt prompt = buildPrompt(request);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            // 使用 blockLast 阻塞式处理流式响应
            chatClient.prompt(prompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        // 空值检查
                        if (response == null || response.getResult() == null
                                || response.getResult().getOutput() == null) {
                            return;
                        }
                        String content = response.getResult().getOutput().getText();
                        if (content != null && !content.isEmpty()) {
                            callback.onContent(content);
                        }
                    })
                    .doOnError(error -> callback.onError(error))
                    .doOnComplete(() -> {
                        completed.set(true);
                        callback.onComplete();
                    })
                    .blockLast();  // 阻塞直到流完成
        } catch (Exception e) {
            // 如果是正常的完成或取消，不报错
            if (e.getMessage() != null && (e.getMessage().contains("was cancelled")
                    || e.getMessage().contains("Flux was cancelled"))) {
                log.debug("流式调用被取消或正常完成");
            } else {
                throw e;
            }
        } finally {
            // 确保 onComplete 被调用
            if (!completed.get()) {
                try {
                    callback.onComplete();
                } catch (Exception e) {
                    log.debug("finally块中调用onComplete失败", e);
                }
            }
        }
    }

    private Prompt buildPrompt(ChatRequest request) {
        List<Message> messages = new ArrayList<>();

        for (ChatMessage chatMessage : request.getMessages()) {
            switch (chatMessage.getRole()) {
                case SYSTEM -> messages.add(new SystemMessage(chatMessage.getContent()));
                case USER -> messages.add(new UserMessage(chatMessage.getContent()));
                case ASSISTANT -> messages.add(new AssistantMessage(chatMessage.getContent()));
            }
        }

        // 使用 Spring AI 的 ChatOptions 构建选项
        org.springframework.ai.chat.prompt.ChatOptions options = org.springframework.ai.chat.prompt.ChatOptions.builder()
                .build();

        return new Prompt(messages, options);
    }
}
