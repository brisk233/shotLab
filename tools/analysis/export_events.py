"""导出引擎逐球数据为 CSV（供人工核对）。"""
import json, math, subprocess, sys, os
SRC = sys.argv[1]; RIM = sys.argv[2]; OUT = sys.argv[3]; TOL = sys.argv[4] if len(sys.argv)>4 else "0.55"
r = subprocess.run([sys.executable, "engine_v7.py", SRC, RIM, TOL], capture_output=True, text=True, encoding="utf-8")
try:
    d = json.loads(r.stdout.strip().splitlines()[-1])
except Exception:
    print("engine failed:", r.stdout[-200:], r.stderr[-200:]); sys.exit()
lines = ["序号,出手时刻(s),判定,与筐中心偏差(筐宽倍数),出手距离(m),点数"]
for i,e in enumerate(d["events"], 1):
    lines.append("%d,%.2f,%s,%+.2f,%.2f,%d" % (i, e["t"], "命中" if e["made"] else "不中", e["err"], e["dist"], e["n"]))
lines.append("")
lines.append("汇总,%d投 %d中,," % (d["attempts"], d["made"]))
open(OUT, "w", encoding="utf-8-sig").write("\n".join(lines))
print(os.path.basename(OUT), d["attempts"], "投", d["made"], "中")
