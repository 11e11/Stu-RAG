# Ragent 项目架构目录

> 本文档记录项目的核心架构和模块划分，方便快速定位代码。

## 📁 项目整体结构

```
ragent/
├── bootstrap/              # 业务逻辑层（核心业务代码）
├── framework/              # 框架层（通用基础能力）
├── infra-ai/               # 基础设施层（模型供应商适配）
├── mcp-server/             # MCP 工具服务（独立部署的工具服务）
├── frontend/               # 前端 React 应用
└── resources/              # 配置文件、数据库脚本、文档
```

---

## 🔧 模块一：MCP 工具路由

**功能描述**：集成联网搜索（Tavily）与节假日校历查询等 MCP 工具，通过意图识别将实时数据类问题与静态知识库检索分流。

### 核心代码位置

| 文件 | 说明 |
|:---|:---|
| [McpToolRegistry.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpToolRegistry.java) | MCP 工具注册表接口，管理工具的注册、发现、获取 |
| [DefaultMcpToolRegistry.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/DefaultMcpToolRegistry.java) | 注册表默认实现，自动发现 Spring Bean 中的工具执行器 |
| [McpToolExecutor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpToolExecutor.java) | 工具执行器接口，定义工具调用规范 |
| [McpClientToolExecutor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientToolExecutor.java) | MCP 客户端工具执行器，调用远程 MCP Server |
| [LLMMcpParameterExtractor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/LLMMcpParameterExtractor.java) | 基于 LLM 的参数提取器，从用户问题中提取工具调用参数 |
| [McpClientProperties.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/mcp/McpClientProperties.java) | MCP 客户端配置属性 |

### MCP Server 工具实现（独立服务）

| 文件 | 说明 |
|:---|:---|
| [McpServerConfig.java](mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/config/McpServerConfig.java) | MCP Server 配置 |
| [WeatherMcpExecutor.java](mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/WeatherMcpExecutor.java) | 天气查询工具 |
| [TicketMcpExecutor.java](mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/TicketMcpExecutor.java) | 工单查询工具 |
| [SalesMcpExecutor.java](mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/SalesMcpExecutor.java) | 销售数据查询工具 |
| [Nl2SqlMcpExecutor.java](mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/Nl2SqlMcpExecutor.java) | 自然语言转 SQL 查询工具 |

---

## 🔍 模块二：稠密+稀疏混合检索

**功能描述**：针对知识库类问题，意图识别驱动检索通道选择，每路通道内引入 Milvus 稀疏向量（BM25）与稠密向量混合检索，RRF 融合多路结果。

### 核心代码位置

| 文件 | 说明 |
|:---|:---|
| [SearchChannel.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchChannel.java) | 检索通道接口，定义通道的启用判断和检索逻辑 |
| [VectorGlobalSearchChannel.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java) | 向量全局检索通道 |
| [IntentDirectedSearchChannel.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java) | 意图定向检索通道 |
| [KeywordSearchChannel.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/KeywordSearchChannel.java) | 关键词检索通道 |
| [AbstractParallelRetriever.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/AbstractParallelRetriever.java) | 并行检索抽象基类 |
| [MultiChannelRetrievalEngine.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java) | 多通道检索引擎，调度多个通道并行执行 |

### 混合检索与融合

| 文件 | 说明 |
|:---|:---|
| [FusionStrategy.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/fusion/FusionStrategy.java) | 融合策略接口 |
| [RRFFusionStrategy.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/fusion/RRFFusionStrategy.java) | RRF（Reciprocal Rank Fusion）融合策略实现 |
| [WeightedSumFusionStrategy.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/fusion/WeightedSumFusionStrategy.java) | 加权求和融合策略实现 |
| [HybridFusionPostProcessor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/HybridFusionPostProcessor.java) | 混合融合后处理器 |
| [RerankPostProcessor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessor.java) | 重排序后处理器（Cross-Encoder） |
| [DeduplicationPostProcessor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java) | 去重后处理器 |

### 检索服务

| 文件 | 说明 |
|:---|:---|
| [RetrievalEngine.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java) | 检索引擎接口 |
| [MilvusRetrieverService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java) | Milvus 向量检索服务 |
| [PgRetrieverService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java) | PostgreSQL 检索服务 |

---

## 💬 模块三：会话记忆管理

**功能描述**：多轮对话中检索结果与历史上下文共同组装 Prompt，滑动窗口保留最近 N 轮对话，超出阈值时调用 LLM 自动摘要压缩历史并持久化。

### 核心代码位置

| 文件 | 说明 |
|:---|:---|
| [ConversationMemoryService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/ConversationMemoryService.java) | 对话记忆服务接口，定义加载、追加等核心方法 |
| [DefaultConversationMemoryService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/DefaultConversationMemoryService.java) | 记忆服务默认实现，并行加载摘要和历史记录 |
| [ConversationMemoryStore.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/ConversationMemoryStore.java) | 记忆存储接口 |
| [JdbcConversationMemoryStore.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java) | 基于 JDBC 的记忆持久化实现 |
| [ConversationMemorySummaryService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/ConversationMemorySummaryService.java) | 摘要服务接口，管理对话历史的压缩摘要 |
| [JdbcConversationMemorySummaryService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemorySummaryService.java) | 基于 JDBC 的摘要持久化实现 |

### 记忆管理关键机制

- **滑动窗口**：保留最近 N 轮对话，超出阈值触发压缩
- **自动摘要**：调用 LLM 将历史对话压缩为摘要
- **用户级隔离**：多学生并发问答记忆不串流
- **TTL 过期**：Redis 管理会话过期清理

---

## 📥 模块四：文档入库 Pipeline

**功能描述**：实现可扩展节点编排（解析→分块→向量化→写入 Milvus），教务政策文档按条款粒度独立分块，RocketMQ 异步解耦入库任务。

### 核心代码位置

#### Pipeline 引擎

| 文件 | 说明 |
|:---|:---|
| [IngestionEngine.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java) | 流水线执行引擎，基于节点连线的链式执行 |
| [ConditionEvaluator.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/ConditionEvaluator.java) | 条件评估器，支持节点条件执行 |
| [NodeOutputExtractor.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/NodeOutputExtractor.java) | 节点输出提取器 |

#### 入库节点实现

| 文件 | 说明 |
|:---|:---|
| [IngestionNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IngestionNode.java) | 入库节点接口（模板方法模式） |
| [FetcherNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/FetcherNode.java) | 文档获取节点 |
| [ParserNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ParserNode.java) | 文档解析节点（PDF、Word、Markdown 等） |
| [ChunkerNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ChunkerNode.java) | 文本分块节点 |
| [EnhancerNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnhancerNode.java) | 文档增强节点（可选） |
| [EnricherNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnricherNode.java) | 分块丰富节点（可选） |
| [IndexerNode.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java) | 索引写入节点（写入 Milvus） |

#### 文档解析器

| 文件 | 说明 |
|:---|:---|
| [DocumentParser.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/DocumentParser.java) | 文档解析器接口 |
| [TikaDocumentParser.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/TikaDocumentParser.java) | Apache Tika 解析器（支持多种格式） |
| [MarkdownDocumentParser.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/MarkdownDocumentParser.java) | Markdown 解析器 |

#### 文本分块策略

| 文件 | 说明 |
|:---|:---|
| [ChunkingStrategy.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkingStrategy.java) | 分块策略接口 |
| [FixedSizeTextChunker.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/strategy/FixedSizeTextChunker.java) | 固定大小分块 |
| [StructureAwareTextChunker.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/strategy/StructureAwareTextChunker.java) | 结构感知分块（按段落、标题等） |

#### 文档获取策略

| 文件 | 说明 |
|:---|:---|
| [DocumentFetcher.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/strategy/fetcher/DocumentFetcher.java) | 文档获取器接口 |
| [LocalFileFetcher.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/strategy/fetcher/LocalFileFetcher.java) | 本地文件获取 |
| [HttpUrlFetcher.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/strategy/fetcher/HttpUrlFetcher.java) | HTTP URL 获取 |
| [S3Fetcher.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/strategy/fetcher/S3Fetcher.java) | S3 存储获取 |
| [FeishuFetcher.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/strategy/fetcher/FeishuFetcher.java) | 飞书文档获取 |

---

## 🏗️ 其他核心模块

### 意图识别

| 文件 | 说明 |
|:---|:---|
| [IntentTreeService.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/IntentTreeService.java) | 意图树服务 |
| [IntentParallelRetriever.java](bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java) | 意图驱动的并行检索 |

### 模型管理

| 目录 | 说明 |
|:---|:---|
| [infra-ai/](infra-ai/) | 模型供应商适配层，屏蔽不同模型差异 |

### 基础框架

| 目录 | 说明 |
|:---|:---|
| [framework/](framework/) | 通用基础能力：异常体系、分布式 ID、用户上下文透传、SSE 封装等 |

---

## 📊 数据库设计

数据库脚本位于：[resources/database/](resources/database/)

核心业务表：
- 会话表（conversation）
- 消息表（message）
- 知识库表（knowledge_base）
- 文档表（knowledge_document）
- 分块表（knowledge_chunk）
- 意图树表（intent_tree）
- 入库流水线表（ingestion_pipeline）
- 入库任务表（ingestion_task）
- 链路追踪表（trace）

---

## 🚀 快速定位指南

| 需求 | 查找路径 |
|:---|:---|
| 查看 MCP 工具如何注册 | `bootstrap/.../rag/core/mcp/` |
| 查看检索通道如何扩展 | `bootstrap/.../retrieve/channel/SearchChannel.java` |
| 查看 RRF 融合算法 | `bootstrap/.../retrieve/channel/fusion/RRFFusionStrategy.java` |
| 查看对话记忆如何加载 | `bootstrap/.../memory/DefaultConversationMemoryService.java` |
| 查看入库节点如何实现 | `bootstrap/.../ingestion/node/IngestionNode.java` |
| 查看流水线执行逻辑 | `bootstrap/.../ingestion/engine/IngestionEngine.java` |

---

*最后更新：2026-06-09*
