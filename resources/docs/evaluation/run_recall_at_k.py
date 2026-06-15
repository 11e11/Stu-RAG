"""
Recall@K 评测脚本
==================
衡量 RAG 检索系统在 top-K 结果中命中了多少相关文档。

前置条件：
  1. 先运行 batch_collect.py 收集 RAG 回答（会保存 contexts）
  2. ragas_eval_dataset.json 已标注 relevant_docs

计算公式：
  Recall@K = |relevant_docs ∩ retrieved_top_K| / |relevant_docs|

来源判定：
  通过 chunk 内容与知识库文档的子串匹配来判断 chunk 来源。

使用方式：
  python resources/docs/evaluation/run_recall_at_k.py
"""

import argparse
import json
import os


# 知识库文档根目录
KNOWLEDGE_DIR = "resources/docs/knowledge"

# 已知文档列表（文件名 -> 完整路径）
DOC_MAP = {
    "人事制度.md": "group/hr/人事制度.md",
    "公司规章制度.md": "group/hr/公司规章制度.md",
    "员工培训.md": "group/hr/员工培训.md",
    "招聘信息.md": "group/hr/招聘信息.md",
    "薪资与福利政策.md": "group/hr/薪资与福利政策.md",
    "IT支持.md": "group/it/IT支持.md",
    "开票信息.md": "group/group-finance/开票信息.md",
    "互联网保险系统数据安全规范.md": "biz/biz-ins/互联网保险系统数据安全规范.md",
    "OA系统数据安全规范文档.md": "biz/biz-oa/OA系统数据安全规范文档.md",
}


def load_knowledge_docs():
    """加载所有知识库文档的前 500 字作为指纹"""
    doc_fingerprints = {}
    for filename, rel_path in DOC_MAP.items():
        full_path = os.path.join(KNOWLEDGE_DIR, rel_path)
        if os.path.exists(full_path):
            with open(full_path, encoding="utf-8") as f:
                content = f.read()
            # 取每个章节的标题作为指纹
            lines = content.split("\n")
            fingerprints = []
            for line in lines:
                line = line.strip()
                if line.startswith("#") and len(line) > 2:
                    fingerprints.append(line.lstrip("#").strip())
            doc_fingerprints[filename] = {
                "content": content,
                "headlines": fingerprints,
            }
    return doc_fingerprints


def identify_chunk_source(chunk: str, doc_fingerprints: dict) -> str:
    """通过内容匹配判断 chunk 来自哪个文档"""
    best_doc = ""
    best_score = 0

    chunk_lower = chunk.lower()[:500]  # 取前 500 字做匹配

    for filename, doc_info in doc_fingerprints.items():
        score = 0
        # 方法1: 检查章节标题是否出现在 chunk 中
        for headline in doc_info["headlines"]:
            if headline.lower() in chunk_lower:
                score += 2

        # 方法2: 检查文档内容的连续子串是否在 chunk 中出现
        content = doc_info["content"]
        # 取 3 个 50 字的片段做子串匹配
        sample_len = 50
        for i in range(0, min(len(content), 1000), 300):
            snippet = content[i:i + sample_len].strip()
            if len(snippet) > 20 and snippet in chunk:
                score += 1

        if score > best_score:
            best_score = score
            best_doc = filename

    return best_doc if best_score > 0 else ""


def normalize_doc_path(doc_path: str) -> str:
    """标准化文档路径，只保留文件名部分用于匹配"""
    return doc_path.split("/")[-1] if "/" in doc_path else doc_path


def main():
    parser = argparse.ArgumentParser(description="Recall@K 评测")
    parser.add_argument("--dataset", default="resources/docs/evaluation/ragas_eval_dataset.json",
                        help="包含 relevant_docs 标注的数据集")
    parser.add_argument("--answers", default="resources/docs/evaluation/ragas_eval_with_answers_forhybrid.json",
                        help="RAG 回答结果（包含检索到的 contexts）")
    parser.add_argument("--output", default="resources/docs/evaluation/recall_at_k_report_forhybrid.json")
    args = parser.parse_args()

    # ---- 1. 加载数据 ----
    print(f"[Load] 加载数据集: {args.dataset}")
    with open(args.dataset, encoding="utf-8") as f:
        dataset = json.load(f)

    print(f"[Load] 加载 RAG 回答: {args.answers}")
    with open(args.answers, encoding="utf-8") as f:
        answers = json.load(f)

    answer_map = {item["id"]: item for item in answers}

    # ---- 2. 加载知识库文档指纹 ----
    print("[Load] 加载知识库文档指纹...")
    doc_fingerprints = load_knowledge_docs()
    print(f"[Load] 已加载 {len(doc_fingerprints)} 个文档")

    # ---- 3. 计算 Recall@K ----
    K_VALUES = [1, 3, 5, 10, 15, 20]
    results = []

    print(f"\n[Eval] 开始计算 Recall@K，共 {len(dataset)} 个样本")
    print(f"[Eval] K 值: {K_VALUES}")
    print("-" * 60)

    for item in dataset:
        qid = item["id"]
        relevant_docs = item.get("relevant_docs", [])
        if not relevant_docs:
            continue

        answer_item = answer_map.get(qid, {})
        contexts = answer_item.get("contexts", [])

        # 标准化 relevant_docs 为文件名
        relevant_filenames = set(normalize_doc_path(d) for d in relevant_docs)

        # 通过内容匹配识别每个 chunk 的来源文档
        retrieved_docs = []
        for chunk in contexts:
            source = identify_chunk_source(chunk, doc_fingerprints)
            if source:
                retrieved_docs.append(source)

        # 去重并保持顺序（模拟 top-K）
        seen = set()
        unique_retrieved = []
        for doc in retrieved_docs:
            if doc not in seen:
                seen.add(doc)
                unique_retrieved.append(doc)

        # 计算各个 K 值的 Recall
        recall_at_k = {}
        for k in K_VALUES:
            top_k = set(unique_retrieved[:k])
            hits = relevant_filenames & top_k
            recall = len(hits) / len(relevant_filenames) if relevant_filenames else 0.0
            recall_at_k[f"recall@{k}"] = round(recall, 4)

        results.append({
            "id": qid,
            "question": item["question"][:60],
            "relevant_docs": list(relevant_filenames),
            "retrieved_docs": unique_retrieved[:10],
            "retrieved_docs_count": len(unique_retrieved),
            **recall_at_k,
        })

    # ---- 4. 汇总统计 ----
    print("\n" + "=" * 60)
    print("Recall@K 评测结果")
    print("=" * 60)

    summary = {}
    for k in K_VALUES:
        key = f"recall@{k}"
        values = [r[key] for r in results]
        avg = sum(values) / len(values) if values else 0
        perfect = sum(1 for v in values if v >= 1.0)
        summary[key] = {
            "mean": round(avg, 4),
            "perfect_recall": perfect,
            "total": len(results),
        }
        print(f"  {key}: mean={avg:.4f}, perfect={perfect}/{len(results)}")

    print("=" * 60)

    # ---- 5. 找出低分样本 ----
    print("\n[Low Recall@5 Items]")
    for r in results:
        if r["recall@5"] < 1.0:
            print(f"  Q{r['id']}: {r['question']}...")
            print(f"    relevant={r['relevant_docs']}, retrieved={r['retrieved_docs'][:5]}")
            print(f"    recall@5={r['recall@5']}")

    # ---- 6. 保存报告 ----
    report = {
        "summary": summary,
        "samples": results,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n[Done] 报告已保存: {args.output}")


if __name__ == "__main__":
    main()
