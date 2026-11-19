"""engine v7：放宽丢帧关联，并在有筐后观测时复核是否真正向下穿筐。"""
import json, math, sys
SRC = sys.argv[1]; RIM = tuple(float(x) for x in sys.argv[2].split(","))
data = json.load(open(SRC)); W,H = data["w"], data["h"]
rim_w = RIM[2]-RIM[0]; rim_cy=(RIM[1]+RIM[3])/2; rim_cx=(RIM[0]+RIM[2])/2
px_per_m = (rim_w*W)/0.4572
TOL = float(sys.argv[3]) if len(sys.argv)>3 else 0.55
TRACK_GATE = 0.08
def center(b): return ((b[0]+b[2])/2,(b[1]+b[3])/2)
trk=[]; active=[]
for fr in data["frames"]:
    t=fr["t"]; na=[]; used=set()
    for idx in active:
        tr=trk[idx]; lt,lx,ly=tr[-1]; dt=max(1e-3,t-lt)
        if dt>0.35: continue
        best=None
        for ci,c in enumerate(fr["cands"]):
            if ci in used: continue
            cx,cy=center(c); dd=math.hypot(cx-lx,cy-ly)
            if dd<TRACK_GATE and (best is None or dd<best[0]): best=(dd,ci,cx,cy)
        if best:
            used.add(best[1]); tr.append((t,best[2],best[3])); na.append(idx)
    for ci,c in enumerate(fr["cands"]):
        if ci in used: continue
        cx,cy=center(c); trk.append([(t,cx,cy)]); na.append(len(trk)-1)
    active=na
def fit(pts):
    t0=pts[0][0]; S=[0.0]*5; Sy=[0.0]*3
    for (t,x,y) in pts:
        dt=t-t0; p=1.0
        for k in range(5): S[k]+=p; p*=dt
        for k in range(3): Sy[k]+=(dt**k)*y
    A=[[S[4],S[3],S[2],Sy[2]],[S[3],S[2],S[1],Sy[1]],[S[2],S[1],S[0],Sy[0]]]
    for col in range(3):
        piv=max(range(col,3), key=lambda r: abs(A[r][col]))
        if abs(A[piv][col])<1e-12: return None
        A[col],A[piv]=A[piv],A[col]
        d=A[col][col]
        for k in range(col,4): A[col][k]/=d
        for r in range(3):
            if r==col: continue
            f=A[r][col]
            for k in range(col,4): A[r][k]-=f*A[col][k]
    return A[0][3],A[1][3],A[2][3],t0
def observed_cross(tr, apex):
    saw_cross = False
    for i in range(max(1, apex+1), len(tr)):
        previous, current = tr[i-1], tr[i]
        if current[0]-previous[0] > 0.8: continue
        if previous[2] >= rim_cy or current[2] < rim_cy: continue
        span = current[2]-previous[2]
        if abs(span) < 1e-6: continue
        fraction = min(1.0, max(0.0, (rim_cy-previous[2])/span))
        cross_x = previous[1] + (current[1]-previous[1])*fraction
        saw_cross = True
        if abs(cross_x-rim_cx) <= TOL*rim_w: return True
    if saw_cross: return False
    below = [p for p in tr[apex+1:] if p[2] >= RIM[3] and p[0]-tr[apex][0] <= 1.6]
    if not below: return None
    return abs(below[0][1]-rim_cx) <= TOL*rim_w
ev=[]
for tr in trk:
    if len(tr)<5: continue
    if tr[-1][0]-tr[0][0] < 0.3: continue
    pk=min(p[2] for p in tr)
    if pk > rim_cy + 1.4*rim_w: continue
    apex=min(range(len(tr)), key=lambda i: tr[i][2])
    arc=tr[:min(len(tr), apex+6)]
    if len(arc)<5: continue
    f=fit(arc)
    if f is None: continue
    a,b,c,t0=f
    if a<=0: continue
    disc=b*b-4*a*(c-rim_cy)
    if disc<0: continue
    sq=math.sqrt(disc); roots=[(-b+sq)/(2*a),(-b-sq)/(2*a)]
    t_hit=min([r for r in roots if r>0] or [None]) if any(r>0 for r in roots) else None
    if t_hit is None or t_hit>3.5: continue
    xs=[p[1] for p in arc]; ts=[p[0]-t0 for p in arc]; n=len(ts)
    st=sum(ts); sx=sum(xs); stt=sum(t*t for t in ts); stx=sum(t*x for t,x in zip(ts,xs))
    den=n*stt-st*st
    if abs(den)<1e-12: continue
    kx=(n*stx-st*sx)/den; bx=(sx-kx*st)/n
    x_hit=kx*t_hit+bx
    rel=tr[0]
    dist=math.hypot((rel[1]-rim_cx)*W,(rel[2]-rim_cy)*H)/px_per_m
    predicted=abs(x_hit-rim_cx)<=TOL*rim_w
    observed=observed_cross(tr, apex)
    made=predicted and observed is not False
    rise = tr[-1][2] < tr[0][2]
    ev.append({"t":round(rel[0],1),"made":made,"err":round((x_hit-rim_cx)/rim_w,2),
               "observed":observed,"dist":round(dist,2),"rise_only":rise,"n":len(tr)})
ev.sort(key=lambda e:e["t"])
uniq=[]
for e in ev:
    if uniq and e["t"]-uniq[-1]["t"]<1.2:
        if e["made"] and not uniq[-1]["made"]: uniq[-1]=e
        continue
    uniq.append(e)
print(json.dumps({"attempts":len(uniq),"made":sum(1 for e in uniq if e["made"]),
                  "rise_only":sum(1 for e in uniq if e["rise_only"]),"events":uniq}, ensure_ascii=False))
