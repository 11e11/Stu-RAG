"""
RAGAS 批量评测数据收集脚本
============================
功能：
  1. 登录 Ragent 获取 sa-token
  2. 逐条调用 RAG SSE 接口获取 answer
  3. 通过 Trace API 获取检索到的 contexts
  4. 输出包含 question / answer / ground_truth / contexts 的 JSON 文件

RAGAS 评估所需字段：
  - question: 用户问题
  - answer: RAG 系统生成的回答
  - contexts: RAG 系统检索到的上下文（list of strings）
  - ground_truth: 标准答案

使用方式：
  pip install requests
  python resources/docs/evaluation/batch_collect.py

  可选参数：
    --base-url  http://localhost:9090/api/ragent
    --username   admin
    --password   admin
    --input      resources/docs/evaluation/ragas_eval_dataset.json
    --output     resources/docs/evaluation/ragas_eval_with_answers.json
    --delay      1.0 (每次请求间隔秒数，避免限流)
"""

import argparse
import json
import time
import requests


def login(base_url: str, username: str, password: str) -> str:
    """登录获取 sa-token"""
    resp = requests.post(
        f"{base_url}/auth/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    resp.raise_for_status()
    data = resp.json()
    if data.get("code") != 200 and data.get("success") is not True:
        raise RuntimeError(f"登录失败: {data}")
    token = data["data"]["token"]
    print(f"[Login] 登录成功, userId={data['data']['userId']}")
    return token


def call_rag_sse(base_url: str, token: str, question: str, conversation_id: str) -> dict:
    """
    调用 RAG SSE 接口，返回 {"answer": str, "trace_id": str}
    """
    headers = {"Authorization": token}
    params = {
        "question": question,
        "conversationId": conversation_id,
        "deepThinking": "false",
    }

    answer_parts = []
    trace_id = None

    try:
        resp = requests.get(
            f"{base_url}/rag/v3/chat",
            headers=headers,
            params=params,
            stream=True,
            timeout=120,
        )
        resp.raise_for_status()

        # Ragent SSE 格式:
        #   event: meta      data: {"conversationId":"...","taskId":"..."}
        #   event: message   data: {"type":"response","delta":"文本片段"}
        #   event: finish    data: {"messageId":"...","title":"..."}
        #   event: done      data: "[DONE]"
        current_event = None

        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                current_event = None
                continue

            # 解析 event 行
            if line.startswith("event:"):
                current_event = line[6:].strip()
                continue

            # 解析 data 行
            if line.startswith("data:"):
                raw = line[5:].strip()
                if not raw:
                    continue

                # done 事件
                if current_event == "done":
                    break

                # meta 事件：提取 taskId 作为 trace_id
                if current_event == "meta":
                    try:
                        meta = json.loads(raw)
                        trace_id = meta.get("taskId")
                    except json.JSONDecodeError:
                        pass

                # message 事件：提取增量文本
                if current_event == "message":
                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    msg_type = msg.get("type", "")
                    delta = msg.get("delta", "")
                    if msg_type == "response" and delta:
                        answer_parts.append(delta)

    except requests.exceptions.Timeout:
        answer_parts.append("[TIMEOUT]")
    except Exception as e:
        answer_parts.append(f"[ERROR: {e}]")

    answer = "".join(answer_parts).strip()
    return {"answer": answer, "trace_id": trace_id}


def fetch_trace_contexts(base_url: str, token: str, conversation_id: str) -> list:
    """从 Trace API 获取检索到的上下文（使用 conversationId 查询）"""
    if not conversation_id:
        return []
    headers = {"Authorization": token}
    try:
        # 使用分页查询 API，按 conversationId 查询
        resp = requests.get(
            f"{base_url}/rag/traces/runs",
            headers=headers,
            params={"conversationId": conversation_id, "current": 1, "size": 10},
            timeout=10,
        )
        if resp.status_code != 200:
            return []
        data = resp.json()

        inner_data = data.get("data")
        if inner_data is None:
            return []

        # 分页查询返回的是 {records: [...], total: ...}
        records = inner_data.get("records", [])
        if not records:
            return []

        # 获取第一条记录的 traceId
        first_record = records[0]
        trace_id = first_record.get("traceId")
        if not trace_id:
            return []

        # 查询 Trace 详情
        detail_resp = requests.get(
            f"{base_url}/rag/traces/runs/{trace_id}",
            headers=headers,
            timeout=10,
        )
        if detail_resp.status_code != 200:
            return []
        detail_data = detail_resp.json()

        detail_inner = detail_data.get("data")
        if detail_inner is None:
            return []

        nodes = detail_inner.get("nodes")
        if not isinstance(nodes, list):
            return []

        contexts = []
        for node in nodes:
            if not isinstance(node, dict):
                continue
            extra = node.get("extraData", "")
            if extra:
                try:
                    extra_obj = json.loads(extra)
                    # extraData 可能直接就是 chunks 数组，或者包含 chunks 字段
                    chunks = extra_obj if isinstance(extra_obj, list) else extra_obj.get("chunks", extra_obj.get("contextChunks", []))
                    if isinstance(chunks, list):
                        for chunk in chunks:
                            if isinstance(chunk, str):
                                contexts.append(chunk)
                            elif isinstance(chunk, dict):
                                # RetrievedChunk 结构：{"id": "...", "text": "...", "score": ...}
                                contexts.append(chunk.get("text", str(chunk)))
                except (json.JSONDecodeError, TypeError):
                    pass
        return contexts
    except Exception:
        return []


def main():
    parser = argparse.ArgumentParser(description="RAGAS 批量评测数据收集")
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--input", default="resources/docs/evaluation/ragas_eval_dataset_forhybrid.json")
    parser.add_argument("--output", default="resources/docs/evaluation/ragas_eval_with_answers_forhybrid.json")
    parser.add_argument("--delay", type=float, default=1.0, help="每次请求间隔秒数")
    args = parser.parse_args()

    # 1. 加载评测数据集
    print(f"[Load] 加载数据集: {args.input}")
    with open(args.input, encoding="utf-8") as f:
        dataset = json.load(f)
    print(f"[Load] 共 {len(dataset)} 条评测数据")

    # 2. 登录
    token = login(args.base_url, args.username, args.password)

    # 3. 逐条调用 RAG 获取 answer
    results = []
    for i, item in enumerate(dataset):
        qid = item["id"]
        question = item["question"]
        print(f"\n[{i+1}/{len(dataset)}] Q{qid}: {question[:50]}...")

        conversation_id = f"eval_batch_{qid}"
        rag_result = call_rag_sse(
            base_url=args.base_url,
            token=token,
            question=question,
            conversation_id=conversation_id,
        )

        # 通过 Trace API 获取检索到的上下文（使用 conversationId 查询）
        contexts = fetch_trace_contexts(args.base_url, token, conversation_id)

        result = {
            "id": qid,
            "question": question,
            "answer": rag_result["answer"],
            "contexts": contexts,
            "ground_truth": item["answer"],
            "metadata": item.get("metadata", {}),
            # 可选：保留原始参考上下文（用于对比分析）
            "reference_contexts": item.get("contexts", []),
        }
        results.append(result)

        # 打印进度
        answer_preview = rag_result["answer"][:80].replace("\n", " ")
        print(f"  -> answer: {answer_preview}...")
        print(f"  -> trace_id: {rag_result['trace_id']}")
        print(f"  -> contexts: {len(contexts)} chunks")

        # 间隔避免限流
        if i < len(dataset) - 1:
            time.sleep(args.delay)

    # 4. 保存结果
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n[Done] 结果已保存到: {args.output}")

    # 5. 输出统计
    total = len(results)
    has_answer = sum(1 for r in results if r["answer"] and not r["answer"].startswith("["))
    has_contexts = sum(1 for r in results if r["contexts"])
    print(f"[Stats] 总数={total}, 有回答={has_answer}, 有上下文={has_contexts}")


if __name__ == "__main__":
    main()
