"""用 Valhalla 稠密走廊线（CN_ROAD_GEOM）稠密化真实路网里的稀疏长弦边。

背景（_city_chord_check.mjs + 交付版对照取证）：
  * 交付版南宁→河内走手写边 E1 的 Valhalla 稠密几何，用户验收「贴路正常」；
  * 现版选真实路网后，金湖东南一带的白沙大道 way 只有 2 点（OSM 制图稀疏），
    Valhalla 在同一路段有拐点（绕湖岸），2 点直连把弯截成 1150m 斜弦——
    用户截图「总有一段斜着穿建筑物」即此；
  * 修复：内部弦 >=400m 的真实边，若两端能落到同一条 Valhalla 参考线上
    （端点距 <=300m、子段离原直线 <=500m，保证是同一条路），几何换成参考子段。

流程（一个脚本跑完，保证可重入）：
  1) E1/E2/E3/E8 的 c 恢复为 CN_ROAD_GEOM 原始 Valhalla 几何（清掉上一轮
     urbanize 的拼接结果，避免叠加）；
  2) 真实边稠密化；
  3) 重跑 refine_corridors（里程校正 + 城区前缀链基于稠密化后的边重新拼接）。

  python tools/densify_valhalla_edges.py            # 改写并备份
  python tools/densify_valhalla_edges.py --dry-run  # 只报告
"""
import json
import math
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
NETWORK = os.path.join(_HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")

from convert_vietnam_pbf import CN_ROAD_GEOM          # Valhalla 原始稠密走廊线
from refine_cn_corridors import (HAND_EDGES, REAL_LEVELS, haversine_km,
                                 poly_len_km, max_gap_m, refine_corridors)

MIN_CHORD_M = 300.0     # 边内部弦长阈值
ENDPOINT_TOL_KM = 0.30  # 边端点到参考线的最大容差
DEVIATION_TOL_KM = 0.50 # 参考子段相对边原直线的最大偏离（防抓隔壁路）


def nearest_on_line(line, p):
    """点到折线最近顶点：返回 (索引, 距离km)。端点吸附用顶点即可，
    参考线点距 50~300m，半格误差远小于容差。"""
    best_i, best_d = -1, 1e18
    for i, q in enumerate(line):
        d = haversine_km(p, q)
        if d < best_d:
            best_d, best_i = d, i
    return best_i, best_d


def pt_seg_km(p, a, b):
    cos = max(0.2, math.cos(math.radians(p[0])))
    ax, ay = a[0], a[1] * cos
    bx, by = b[0], b[1] * cos
    px, py = p[0], p[1] * cos
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 <= 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
    return math.hypot(px - ax - dx * t, py - ay - dy * t) * 111.32


def max_internal_gap_m(g):
    """边几何里「最长的一根被画出来的弦」：c[] 的首尾顶点就是路网节点，相邻顶点
    的每一段都会原样画进导航线，所以候选判据必须看全部相邻顶点间距。
    旧实现用 range(2, len-1) 跳过首尾两段，本意是躲「节点→几何首尾 snap 偏差」，
    但代价是 2~3 点边（整条就是一根 600~1900m 直弦、正是穿楼最狠的那批）算出来恒为
    0、永远够不到阈值被跳过——南宁城区 trunk/secondary 的 1147m、752m 斜弦就是这么漏网的。
    误报交给 densify_edge 的端点吸附(300m)/走廊偏离(500m)/换完不降反升三道护栏挡，
    这里不再靠跳过首尾段来防。"""
    return max((haversine_km(g[i - 1], g[i]) for i in range(1, len(g))),
               default=0.0) * 1000


def dev_from_seg(sub, a, b):
    """子段各顶点到边原直线 (a,b) 的最大偏离 km"""
    return max((pt_seg_km(p, a, b) for p in sub), default=0.0)


def dev_from_polyline(sub, g):
    """子段各顶点到「边自身已有折线 g」的最大偏离 km。
    旧判据 dev_from_seg 只比到端点直线 (a,b)：对一条十几公里、本身带弯的走廊干道
    （南友高速），E1 参考线沿真实弯道走，离首尾直线自然 >500m，于是被误判成「抓了
    隔壁路」而拒绝稠密化——恰恰漏掉了最长的那根 1942m 高速斜弦。改成比到边自身折线：
    只要参考线和本边描绘的是同一条路（沿同一弯走），偏离就小，与道路总长/弯曲度无关；
    真正是隔壁平行路时，本边折线与参考线仍会分开，照样挡得住。两点边退化为直线，与原判据等价。"""
    def to_poly(p):
        return min((pt_seg_km(p, g[i - 1], g[i]) for i in range(1, len(g))), default=0.0)
    return max((to_poly(p) for p in sub), default=0.0)


def densify_edge(e, lines):
    """返回替换后的几何（成功）或 None。只处理两端都能落到同一条参考线上的边。"""
    g = e.get("c")
    if not isinstance(g, list) or len(g) < 2:
        return None
    a, b = g[0], g[-1]
    for line in lines:
        i0, d0 = nearest_on_line(line, a)
        if d0 > ENDPOINT_TOL_KM:
            continue
        i1, d1 = nearest_on_line(line, b)
        if d1 > ENDPOINT_TOL_KM:
            continue
        if i0 == i1:
            continue
        sub = line[i0:i1 + 1] if i0 < i1 else list(reversed(line[i1:i0 + 1]))
        # 子段不能整体漂移出本边走廊（隔壁平行车会被挡掉）：
        # 比到边自身折线而非首尾直线，长弯走廊干道才不会被误杀
        if dev_from_polyline(sub[1:-1], g) > DEVIATION_TOL_KM:
            continue
        new = [list(a)] + [list(p) for p in sub[1:-1]] + [list(b)]
        # 去重相邻重合点
        out = [new[0]]
        for p in new[1:]:
            if haversine_km(out[-1], p) > 1e-7:
                out.append(p)
        return out if len(out) >= 2 else None
    return None


def main():
    dry = "--dry-run" in sys.argv
    with open(NETWORK, encoding="utf-8") as f:
        data = json.load(f)
    nodes, edges = data["nodes"], data["edges"]
    hand = {e["id"]: e for e in edges if e.get("id") in HAND_EDGES}

    # 1) 手写走廊边恢复原始 Valhalla 几何（里程交给第 3 步 refine 重算）
    for hid, line in CN_ROAD_GEOM.items():
        h = hand.get(hid)
        if h and isinstance(line, list) and len(line) >= 2:
            h["c"] = [[p[0], p[1]] for p in line]

    lines = [[[p[0], p[1]] for p in v] for v in CN_ROAD_GEOM.values()
             if isinstance(v, list) and len(v) >= 2]

    # 2) 真实边稠密化：只处理走廊等级、内部弦超阈值的边
    done, skipped = [], 0
    for e in edges:
        if e.get("id") in HAND_EDGES or e.get("customs"):
            continue
        if e.get("roadType") not in REAL_LEVELS:
            continue
        g = e.get("c")
        if not isinstance(g, list) or len(g) < 2:
            continue
        if max_internal_gap_m(g) < MIN_CHORD_M:
            continue
        new = densify_edge(e, lines)
        if new is None:
            skipped += 1
            continue
        old_len = poly_len_km(g)
        old_chord = max_internal_gap_m(g)
        new_chord = max_internal_gap_m(new)
        # Valhalla 参考线在部分段比 OSM 原几何更稀疏（历史产物），换完弦长不降
        # 反升的段直接丢弃，避免越修越糟
        if new_chord > old_chord - 50:
            skipped += 1
            continue
        e["c"] = new
        e["distanceKm"] = round(max(poly_len_km(new), 0.05), 2)
        done.append(f"{e['id']} {e.get('name','')}: {len(g)}pts/{old_len:.2f}km(chord {old_chord:.0f}m) -> "
                    f"{len(new)}pts/{e['distanceKm']}km(chord {new_chord:.0f}m)")

    print(f"[稠密化] 替换 {len(done)} 条边，未覆盖 {skipped} 条")
    for s in done[:40]:
        print("  ", s)
    if len(done) > 40:
        print(f"   ...（其余 {len(done) - 40} 条略）")

    # 3) 走廊精修重跑（基于稠密化后的边重新拼前缀链/全链，并回写里程）
    for line in refine_corridors(nodes, edges):
        print("[走廊精修]", line)

    if dry:
        print("[dry-run] 未写文件")
        return
    with open(NETWORK, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"已写回: {NETWORK}")


if __name__ == "__main__":
    main()
