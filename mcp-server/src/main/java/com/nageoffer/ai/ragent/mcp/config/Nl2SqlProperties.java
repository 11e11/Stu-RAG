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

package com.nageoffer.ai.ragent.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NL2SQL 配置属性
 */
@Data
@ConfigurationProperties(prefix = "nl2sql")
public class Nl2SqlProperties {

    /**
     * LLM API 地址
     */
    private String apiUrl = "https://token-plan-cn.xiaomimimo.com/v1/chat/completions";

    /**
     * LLM API Key
     */
    private String apiKey = "tp-czbtpkp0sn2j8cr94ujj6kasegtyl5yk1fs41511eifvgtif";

    /**
     * 使用的模型
     */
    private String model = "mimo-v2-pro";

    /**
     * 查询结果最大行数
     */
    private int maxRows = 200;

    /**
     * LLM 调用超时时间（秒）
     */
    private int llmTimeoutSeconds = 60;
}
