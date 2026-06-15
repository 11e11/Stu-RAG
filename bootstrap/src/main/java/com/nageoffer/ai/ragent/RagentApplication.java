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

package com.nageoffer.ai.ragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ragent 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {
        "com.nageoffer.ai.ragent.rag.dao.mapper",
        "com.nageoffer.ai.ragent.ingestion.dao.mapper",
        "com.nageoffer.ai.ragent.knowledge.dao.mapper",
        "com.nageoffer.ai.ragent.user.dao.mapper"
})
public class RagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}

// package com.nageoffer.ai.ragent;

// import org.mybatis.spring.annotation.MapperScan;
// import org.springframework.boot.SpringApplication;
// import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.core.env.Environment;
// import org.springframework.scheduling.annotation.EnableScheduling;

// @SpringBootApplication
// @EnableScheduling
// @MapperScan(basePackages = {
//         "com.nageoffer.ai.ragent.rag.dao.mapper",
//         "com.nageoffer.ai.ragent.ingestion.dao.mapper",
//         "com.nageoffer.ai.ragent.knowledge.dao.mapper",
//         "com.nageoffer.ai.ragent.user.dao.mapper"
// })
// public class RagentApplication {

//     public static void main(String[] args) {
//         SpringApplication app = new SpringApplication(RagentApplication.class);
//         app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
//             Environment env = event.getEnvironment();
//             String redisPassword = env.getProperty("spring.data.redis.password");
//             String redissonPassword = env.getProperty("redisson.password");
//             System.out.println("=== spring.data.redis.password: " + redisPassword);
//             System.out.println("=== redisson.password: " + redissonPassword);
//             System.out.println("=== redis host: " + env.getProperty("spring.data.redis.host"));
//             System.out.println("=== redis port: " + env.getProperty("spring.data.redis.port"));
//         });
//         app.run(args);
//     }
// }