"""
RAGAS 评测执行脚本（分批执行 + 实时反馈版）
==========================================
特点：
  1. 每批处理 5 个样本，立即显示结果
  2. 实时显示成功率和解析失败率
  3. 可随时中断，已完成的样本会保存
  4. 支持断点续传

使用方式：
  python resources/docs/evaluation/run_ragas_eval.py
"""

import argparse
import json
import os
import logging
from datetime import datetime

logging.getLogger("ragas").setLevel(logging.ERROR)


def patch_ragas():
    """绕过 ragas 0.1.x 与新版 datasets 的兼容性问题"""
    try:
        import ragas.evaluation as reval
        reval.validate_column_dtypes = lambda ds: None
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description="RAGAS 评测执行（分批执行版）")
    parser.add_argument("--input", default="resources/docs/evaluation/ragas_eval_with_answers.json")
    parser.add_argument("--output", default="resources/docs/evaluation/ragas_eval_report_2.json")
    parser.add_argument("--batch-size", type=int, default=5, help="每批处理的样本数（默认5）")
    parser.add_argument("--resume", action="store_true", help="从中断处继续")
    args = parser.parse_args()

    # ---- 1. 加载评测数据 ----
    print(f"[Load] 加载数据: {args.input}")
    with open(args.input, encoding="utf-8") as f:
        data = json.load(f)
    print(f"[Load] 共 {len(data)} 条")

    # ---- 2. 构建数据 ----
    from datasets import Dataset

    valid_items = []
    for item in data:
        if item.get("answer") and not item["answer"].startswith("["):
            contexts = item.get("contexts", [])
            if contexts and isinstance(contexts[0], dict):
                contexts = [c.get("content", str(c)) for c in contexts]
            valid_items.append({
                "question": item["question"],
                "answer": item["answer"],
                "contexts": contexts,
                "ground_truth": [item["ground_truth"]],
            })

    print(f"[Build] 有效样本: {len(valid_items)}, 跳过: {len(data) - len(valid_items)}")

    if len(valid_items) == 0:
        print("[Error] 没有有效样本，退出")
        return

    # ---- 3. 检查 checkpoint ----
    checkpoint_file = args.output.replace('.json', '_checkpoint.json')
    completed_indices = set()
    all_results = []

    if args.resume and os.path.exists(checkpoint_file):
        try:
            with open(checkpoint_file, "r", encoding="utf-8") as f:
                checkpoint = json.load(f)
            completed_indices = set(checkpoint.get("completed_indices", []))
            all_results = checkpoint.get("results", [])
            print(f"[Resume] 加载已有进度: {len(completed_indices)}/{len(valid_items)} 已完成")
        except Exception as e:
            print(f"[WARN] 加载 checkpoint 失败: {e}")

    pending_indices = [i for i in range(len(valid_items)) if i not in completed_indices]

    if not pending_indices:
        print("[Eval] 所有样本已完成！")
        results = all_results
    else:
        print(f"[Eval] 待处理样本: {len(pending_indices)} 个")

        # ---- 4. 配置模型 ----
        from langchain_openai import ChatOpenAI, OpenAIEmbeddings
        from ragas.llms import LangchainLLMWrapper
        from ragas.embeddings import LangchainEmbeddingsWrapper
        from ragas.run_config import RunConfig
        from ragas.metrics import context_recall

        # mimo 模型配置
        CHAT_MODEL = "mimo-v2.5-pro"
        CHAT_BASE = "https://token-plan-cn.xiaomimimo.com/v1"
        CHAT_KEY = "tp-czbtpkp0sn2j8cr94ujj6kasegtyl5yk1fs41511eifvgtif"

        # SiliconFlow Embedding 配置
        EMB_BASE = "https://api.siliconflow.cn/v1"
        EMB_KEY = "sk-hxbfynpfpvzerweiruelshygdsodzjfstkugaxztxexhytdb"

        chat_llm = ChatOpenAI(
            model=CHAT_MODEL,
            api_key=CHAT_KEY,
            base_url=CHAT_BASE,
            temperature=0.1,
            # max_tokens=2048,      # 增加输出长度，避免评测结果被截断
            n=1,
            timeout=600,          # 单次 HTTP 请求超时 10 分钟
            max_retries=3,
        )
        llm = LangchainLLMWrapper(chat_llm)

        embeddings = None
        try:
            embedding_client = OpenAIEmbeddings(
                api_key=EMB_KEY,
                base_url=EMB_BASE,
                model="BAAI/bge-m3",
                check_embedding_ctx_length=False,
            )
            embeddings = LangchainEmbeddingsWrapper(embedding_client)
            print(f"[Config] Chat LLM: {CHAT_MODEL} (via mimo)")
            print(f"[Config] Embeddings: BAAI/bge-m3 (via SiliconFlow)")
        except Exception as e:
            print(f"[WARN] Embeddings 配置失败: {e}")

        run_config = RunConfig(
            timeout=600,          # 每个 LLM 调用最长等待 10 分钟
            max_workers=3,        # 2 个并发，平衡速度和稳定性
            max_retries=3,
            max_wait=120,         # 重试间最大等待 2 分钟
        )

        metrics = [context_recall]  # 仅召回率，不需要 LLM
        patch_ragas()

        # ---- 5. 分批执行 ----
        from ragas import evaluate

        print(f"\n[Eval] 开始分批评测（每批 {args.batch_size} 个样本）")
        print(f"[Eval] 总样本数: {len(pending_indices)}")
        print("-" * 60)

        batch_start = 0
        while batch_start < len(pending_indices):
            batch_indices = pending_indices[batch_start:batch_start + args.batch_size]
            batch_data = [valid_items[i] for i in batch_indices]

            print(f"\n[Batch] 处理样本 {batch_start + 1}-{min(batch_start + args.batch_size, len(pending_indices))}/{len(pending_indices)}")

            dataset = Dataset.from_dict({
                "question": [d["question"] for d in batch_data],
                "answer": [d["answer"] for d in batch_data],
                "contexts": [d["contexts"] for d in batch_data],
                "ground_truth": [d["ground_truth"] for d in batch_data],
            })

            try:
                result = evaluate(
                    dataset=dataset,
                    metrics=metrics,
                    llm=llm,
                    embeddings=embeddings,
                    run_config=run_config,
                    raise_exceptions=False,
                )

                df = result.to_pandas()

                for j, idx in enumerate(batch_indices):
                    row = df.iloc[j] if j < len(df) else {}
                    sample_result = {
                        "index": idx,
                        "question": valid_items[idx]["question"],
                        "answer": str(valid_items[idx]["answer"])[:200],
                        "context_precision": _safe_float(row.get("context_precision")),
                        "context_recall": _safe_float(row.get("context_recall")),
                        "parse_failed": False,
                    }

                    # 检查是否为 NaN
                    if sample_result["context_precision"] is None and sample_result["context_recall"] is None:
                        sample_result["parse_failed"] = True

                    all_results.append(sample_result)
                    completed_indices.add(idx)

                    cp = sample_result["context_precision"]
                    cr = sample_result["context_recall"]
                    status = "✅" if cp is not None or cr is not None else "❌"
                    print(f"  {status} Q{idx + 1}: cp={cp}, cr={cr}")

            except Exception as e:
                print(f"  [ERROR] 批次评测失败: {e}")
                for idx in batch_indices:
                    all_results.append({
                        "index": idx,
                        "question": valid_items[idx]["question"],
                        "answer": str(valid_items[idx]["answer"])[:200],
                        "context_precision": None,
                        "context_recall": None,
                        "parse_failed": True,
                    })
                    completed_indices.add(idx)

            # 保存 checkpoint
            _save_checkpoint(checkpoint_file, completed_indices, all_results, len(valid_items))
            print(f"  ✅ 批次完成 | 累计: {len(completed_indices)}/{len(valid_items)}")

            batch_start += args.batch_size

        results = all_results

    # ---- 6. 输出结果 ----
    print("\n" + "=" * 60)
    print("RAGAS 评测结果汇总")
    print("=" * 60)

    valid_results = [r for r in results if not r.get("parse_failed")]
    failed_results = [r for r in results if r.get("parse_failed")]

    if valid_results:
        avg_cp = _safe_mean([r["context_precision"] for r in valid_results])
        avg_cr = _safe_mean([r["context_recall"] for r in valid_results])
        print(f"  context_precision: {avg_cp:.4f}")
        print(f"  context_recall:    {avg_cr:.4f}")
    else:
        print("  没有有效的评测结果")

    print(f"\n  总样本: {len(results)}, 成功: {len(valid_results)}, 失败: {len(failed_results)}")
    print("=" * 60)

    # 保存最终报告
    report = {
        "summary": {},
        "samples": results,
    }
    if valid_results:
        report["summary"]["context_precision"] = _safe_mean([r["context_precision"] for r in valid_results])
        report["summary"]["context_recall"] = _safe_mean([r["context_recall"] for r in valid_results])
    report["stats"] = {
        "total": len(results),
        "success": len(valid_results),
        "failed": len(failed_results),
    }

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n[Done] 详细报告已保存: {args.output}")

    # 找出低分条目
    print("\n[Low Score Items]")
    for r in valid_results:
        low = []
        if r["context_precision"] is not None and r["context_precision"] < 0.6:
            low.append(f"cp={r['context_precision']:.2f}")
        if r["context_recall"] is not None and r["context_recall"] < 0.6:
            low.append(f"cr={r['context_recall']:.2f}")
        if low:
            print(f"  Q{r['index'] + 1}: {r['question'][:50]}...")
            print(f"    Low: {', '.join(low)}")


def _safe_float(val):
    """安全转换为 float，NaN 返回 None"""
    if val is None:
        return None
    try:
        import math
        f = float(val)
        return None if math.isnan(f) else round(f, 4)
    except Exception:
        return None


def _safe_mean(values):
    """安全计算平均值，忽略 None"""
    valid = [v for v in values if v is not None]
    return sum(valid) / len(valid) if valid else 0.0


def _save_checkpoint(path, indices, results, total):
    """保存断点续传文件"""
    checkpoint = {
        "completed_indices": sorted(indices),
        "results": results,
        "last_update": datetime.now().isoformat(),
        "stats": {
            "success": len([r for r in results if not r.get("parse_failed")]),
            "total": total,
            "parse_failures": len([r for r in results if r.get("parse_failed")]),
        },
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(checkpoint, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
