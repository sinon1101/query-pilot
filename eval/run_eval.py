#!/usr/bin/env python3
"""
DataAgent 评测脚本：逐条把 questions.txt 里的问题发给 /api/chat（每题独立新对话），
从返回的执行轨迹（steps）自动统计：

- SQL 一次成功率：首条 execute_sql 即执行成功的题目占比（分母 = 实际执行过 SQL 的题目）
- SQL 自愈后成功率：经报错重试后最终有成功 SQL 的题目占比（同上分母）
- 任务完成率：Agent 正常收敛给出结论（success=true）的题目占比（分母 = 全部题目）
- 出图率 / 平均耗时 / 平均 SQL 尝试次数

答案数值正确性不在此脚本内自动判定（样例数据按相对日期动态生成，硬编码期望值会脆化），
结果明细落在 results-*.json，人工抽查 answer 字段即可。

用法（应用需已启动）：
    python eval/run_eval.py [--base http://localhost:8080] [--questions eval/questions.txt]

仅用标准库，无第三方依赖。
"""
import argparse
import json
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path


def load_questions(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [ln.strip() for ln in lines if ln.strip() and not ln.strip().startswith("#")]


def ask(base: str, question: str, timeout: int = 180) -> dict:
    req = urllib.request.Request(
        base + "/api/chat",
        data=json.dumps({"question": question}).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def evaluate_one(resp: dict) -> dict:
    sql_steps = [s for s in resp.get("steps", [])
                 if s.get("type") == "tool_call" and s.get("name") == "execute_sql"]
    sql_attempts = len(sql_steps)
    first_try_ok = sql_attempts > 0 and not sql_steps[0]["error"]
    healed_ok = any(not s["error"] for s in sql_steps)
    return {
        "success": bool(resp.get("success")),
        "sql_attempts": sql_attempts,
        "first_try_ok": first_try_ok,
        "healed_ok": healed_ok,
        "chart": bool(resp.get("chartOption")),
        "rounds": max((s.get("round", 0) for s in resp.get("steps", [])), default=0),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--questions", default=str(Path(__file__).parent / "questions.txt"))
    args = parser.parse_args()

    questions = load_questions(Path(args.questions))
    print(f"共 {len(questions)} 题，目标 {args.base}\n")

    results = []
    for i, q in enumerate(questions, 1):
        start = time.time()
        try:
            resp = ask(args.base, q)
            metrics = evaluate_one(resp)
            record = {"question": q, **metrics,
                      "duration_s": round(time.time() - start, 1),
                      "sql": resp.get("sql"), "answer": resp.get("answer")}
        except Exception as e:  # 网络/超时等按任务失败计
            record = {"question": q, "success": False, "sql_attempts": 0,
                      "first_try_ok": False, "healed_ok": False, "chart": False,
                      "rounds": 0, "duration_s": round(time.time() - start, 1),
                      "sql": None, "answer": f"[请求异常] {e}"}
        results.append(record)
        flag = "✓" if record["success"] else "✗"
        first = "一次成功" if record["first_try_ok"] else (
            "自愈成功" if record["healed_ok"] else ("未执行SQL" if record["sql_attempts"] == 0 else "SQL失败"))
        print(f"[{i:02d}/{len(questions)}] {flag} {first} | SQL×{record['sql_attempts']} "
              f"{record['duration_s']}s | {q}")

    with_sql = [r for r in results if r["sql_attempts"] > 0]
    n, m = len(results), len(with_sql)
    first_ok = sum(r["first_try_ok"] for r in with_sql)
    healed_ok = sum(r["healed_ok"] for r in with_sql)
    done_ok = sum(r["success"] for r in results)
    charts = sum(r["chart"] for r in results)

    print("\n========== 汇总 ==========")
    print(f"执行过 SQL 的题目:   {m}/{n}")
    if m:
        print(f"SQL 一次成功率:      {first_ok}/{m} = {first_ok / m:.0%}")
        print(f"SQL 自愈后成功率:    {healed_ok}/{m} = {healed_ok / m:.0%}")
    print(f"任务完成率:          {done_ok}/{n} = {done_ok / n:.0%}")
    print(f"出图题目数:          {charts}/{n}")
    print(f"平均耗时:            {sum(r['duration_s'] for r in results) / n:.1f}s/题")
    avg_attempts = sum(r["sql_attempts"] for r in with_sql) / m if m else 0
    print(f"平均 SQL 尝试次数:   {avg_attempts:.2f}")

    out = Path(args.questions).parent / f"results-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out}（answer 字段请人工抽查数值正确性）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
