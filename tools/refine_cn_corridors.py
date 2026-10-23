"""手写中国侧走廊边（E1/E2/E3/E8）贴路化精修。

背景（见 tools/_net_variant_plan.mjs 的对照取证）：
  * 手写超级边的 distanceKm 是当年估值（E1 写 120km，几何实测 129.46km），
    导致「画线里程 398km / 声称里程 376km」两套数对不上，且 ETA 跟着偏；
  * 便宜的假里程让 Dijkstra 偏爱手写边，明明广西密集真实路网已加载却不走，
    画线于是沿手写走廊的稀疏跳点走（城区内 420~484m 直连弦）；
  * 走廊几何本身来自 Valhalla 折线的 DP 抽稀，内部最长跳点 1.4~2.3km。

做法：对每条手写走廊边，在原走廊折线 ±BUFFER 的缓冲带内取真实 OSM 边，
做子图 Dijkstra 找出 from->to 的实际道路链，把链上各级真实几何拼接为新走廊
几何，并用真实几何长度回写 distanceKm。找不到贴路链时退一步：至少用原几何
实测长度校正 distanceKm（保证「声称里程 == 画线里程」）。

既可作为独立脚本改写 vietnam-road-network.json（自动备份），
也可被 convert_vietnam_pbf.py 在重生成流程末尾调用，保证两条路径产出一致。

  python tools/refine_cn_corridors.py            # 改写 src/main/resources/data/*.json
  python tools/refine_cn_corridors.py --dry-run  # 只报告不写文件
"""
import heapq
import json
import math
import os
import sys
from collections import defaultdict

_HERE = os.path.dirname(os.path.abspath(__file__))
NETWORK = os.path.join(_HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")

# 手写城际走廊边（有贴路几何、里程估值）；口岸虚拟边 E4/E9/E36 只有几公里，不动
HAND_EDGES = ("E1", "E2", "E3", "E8")
# 真实候选边的等级：走廊是城际干道，只允许同等级道路贴路化
REAL_LEVELS = {"motorway", "trunk", "primary", "secondary"}
# 新链长度上限（相对原走廊几何长度）：绕行太多说明最短链走的不是同一条走廊
MAX_DETOUR = 1.12
# 贴路链允许偏离原走廊的最大距离（km）：南宁端真实链会绕上民族大道出城，
# 缓冲带不能卡死在原走廊 ±2.5km，否则 NN/CZ 锚点在真实图里直接孤立
MAX_OFF_CORRIDOR_KM = 20.0
# 南宁建成区窗口：手写边在真实网不可达时（崇左侧孤岛），城区前缀段用真实路网
# 链替换——Valhalla 几何城区内 420m～1.4km 弦、弦中点离路面最远 210m，是
# 「导航线斜穿建筑物」（用户截图金湖/百色大厦一带）的直接根源
CITY_BOX = (22.74, 22.88, 108.24, 108.46)
CITY_OFF_KM = 5.0     # 前缀链偏离原走廊上限（城区内不允许绕 20km 上高速）
CITY_DETOUR = 1.8     # 前缀链长相对原城区段折线长的绕行上限
CITY_EXIT_KM = 1.2    # 链终点到城区出口点的最大距离（再远收尾弦又穿楼）


def haversine_km(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371.0088 * 2 * math.asin(math.sqrt(h))


def poly_len_km(pts):
    return sum(haversine_km(pts[i - 1], pts[i]) for i in range(1, len(pts)))


def max_gap_m(pts):
    return max((haversine_km(pts[i - 1], pts[i]) for i in range(1, len(pts))), default=0.0) * 1000


def pt_seg_km(p, a, b):
    """点到线段距离（km）：局部等距投影，走廊尺度下误差可忽略。
    注意判据必须用「线段」而不是「最近顶点」——顶点间距可达几公里时，
    按顶点判读会把贴着直路走的稀疏几何误判成离路几百米。"""
    cos = max(0.2, math.cos(math.radians(p[0])))
    ax, ay = a[0], a[1] * cos
    bx, by = b[0], b[1] * cos
    px, py = p[0], p[1] * cos
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 <= 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
    return math.hypot(px - ax - dx * t, py - ay - dy * t) * 111.32


class CorridorIndex:
    """走廊折线的线段网格索引：9.8 万条边逐条对 739 段走廊做暴力比对要跑几分钟，
    网格（0.05°≈5.5km）把单次判定降到常数级邻域。"""

    def __init__(self, line, cell=0.05):
        self.cell = cell
        self.grid = defaultdict(list)
        for i in range(1, len(line)):
            a, b = line[i - 1], line[i]
            la0, la1 = sorted((a[0], b[0]))
            lo0, lo1 = sorted((a[1], b[1]))
            for x in range(int(la0 // cell), int(la1 // cell) + 1):
                for y in range(int(lo0 // cell), int(lo1 // cell) + 1):
                    self.grid[(x, y)].append((a, b))

    def within(self, p, limit_km):
        xi, yi = int(p[0] // self.cell), int(p[1] // self.cell)
        for x in (xi - 1, xi, xi + 1):
            for y in (yi - 1, yi, yi + 1):
                for a, b in self.grid.get((x, y), ()):
                    if pt_seg_km(p, a, b) <= limit_km:
                        return True
        return False


def edge_geom(e, pos):
    """边的可画折线：优先存储几何，缺失时退化为端点直连（与 RouteService.toCoords 同口径）"""
    g = e.get("c")
    if isinstance(g, list) and len(g) >= 2:
        return [[p[0], p[1]] for p in g]
    return [pos[e["fromNodeId"]], pos[e["toNodeId"]]]


def urbanize_prefix(h, pos, old_geom, dist, best, idx):
    """真实网全程不可达时的兜底：手写边仍是唯一连通保障不能删，但把走廊折线
    落在南宁建成区窗口内的前缀段换成真实路网链（城区网络密集、从 NN 可达），
    出城后的干道段保持原样。链拼不出/绕行离谱/离出口点太远则返回 None。
    dist/best 复用全图 Dijkstra 的结果（not-found 分支里 pq 已穷尽，dist 就是
    src 所在连通分量的完整可达表）。"""
    src = h["fromNodeId"]
    if src not in dist:
        return None
    inside = [i for i, p in enumerate(old_geom)
              if CITY_BOX[0] <= p[0] <= CITY_BOX[1] and CITY_BOX[2] <= p[1] <= CITY_BOX[3]]
    if not inside or inside[0] != 0:
        return None                      # 起点不在城区，窗口判据不适用
    k = inside[-1] + 1
    if k >= len(old_geom):
        return None                      # 整条走廊都在城区内（不应发生）
    exit_pt = old_geom[k]                # 首个出城点：链在此接回原走廊
    city_len = poly_len_km(old_geom[:k + 1])
    tgt, tgt_d = None, 1e9
    for nid in dist:
        d = haversine_km(pos[nid], exit_pt)
        if d < tgt_d:
            tgt_d, tgt = d, nid
    if tgt is None or tgt_d > CITY_EXIT_KM or dist[tgt] > city_len * CITY_DETOUR:
        return None
    chain, u = [], tgt
    while u != src:
        pu, e = best[u]
        chain.append((pu, u, e))
        u = pu
    chain.reverse()
    new_geom = [pos[src]]
    for _, v, e in chain:
        g = edge_geom(e, pos)
        if haversine_km(g[0], new_geom[-1]) > haversine_km(g[-1], new_geom[-1]):
            g = g[::-1]
        for p in g[1:]:
            if not idx.within(p, CITY_OFF_KM):
                return None              # 城区前缀绕出走廊带，宁可不改
            if haversine_km(new_geom[-1], p) < 1e-7:
                continue
            new_geom.append(p)
        dnode = haversine_km(new_geom[-1], pos[v])
        if dnode > 1e-7:
            if dnode < 0.05:
                new_geom[-1] = pos[v]
            else:
                new_geom.append(pos[v])
    if haversine_km(new_geom[-1], exit_pt) > CITY_EXIT_KM:
        return None
    new_geom.append(exit_pt)
    new_geom.extend(p for p in old_geom[k + 1:] if haversine_km(new_geom[-1], p) > 1e-7)
    h["c"] = new_geom
    return poly_len_km(new_geom)


def refine_corridors(nodes, edges):
    """就地修改 edges（手写走廊边的 c 与 distanceKm），返回每条边的处理报告"""
    pos = {n["id"]: [n["latitude"], n["longitude"]] for n in nodes}
    hand = {e["id"]: e for e in edges if e.get("id") in HAND_EDGES}
    reports = []

    # 真实边全局邻接表（一次性构建）：手写锚点 NN/CZ/PX/YGG/DX 在 generate_source 里
    # 已绑到真实 OSM 节点（osm_to_anchor），所以字母节点在真实图里有边，
    # 不必再靠「最近节点」二次搭桥。
    real_adj = {}
    for e in edges:
        if e.get("id") in HAND_EDGES or e.get("customs"):
            continue
        if e.get("roadType") not in REAL_LEVELS:
            continue
        u, v = e["fromNodeId"], e["toNodeId"]
        if u not in pos or v not in pos:
            continue
        g = [[p[0], p[1]] for p in e["c"]] if isinstance(e.get("c"), list) and len(e["c"]) >= 2 \
            else [pos[u], pos[v]]
        w = max(poly_len_km(g), 0.05)
        real_adj.setdefault(u, []).append((v, w, e, g))
        real_adj.setdefault(v, []).append((u, w, e, list(reversed(g))))

    for hid in HAND_EDGES:
        h = hand.get(hid)
        if not h or h["fromNodeId"] not in pos or h["toNodeId"] not in pos:
            reports.append(f"{hid}: 缺边或缺端点，跳过")
            continue
        old_geom = edge_geom(h, pos)
        old_len = poly_len_km(old_geom)
        # 粗筛 bbox：走廊两端点外扩 0.4°，越界的真实边直接不参与（防跨半图绕行）
        la = [p[0] for p in old_geom]
        lo = [p[1] for p in old_geom]
        box = (min(la) - 0.4, max(la) + 0.4, min(lo) - 0.4, max(lo) + 0.4)
        idx = CorridorIndex(old_geom, cell=0.2)
        # 真实图 Dijkstra：src -> to（成本 = 实测几何长度）
        src, dst = h["fromNodeId"], h["toNodeId"]
        dist, best = {src: 0.0}, {}
        pq = [(0.0, src)]
        found = False
        while pq:
            d, u = heapq.heappop(pq)
            if u == dst:
                found = True
                break
            if d > dist.get(u, math.inf):
                continue
            for v, w, e, _g in real_adj.get(u, ()):
                if v not in pos or not (box[0] <= pos[v][0] <= box[1] and box[2] <= pos[v][1] <= box[3]):
                    continue
                nd = d + w
                if nd < dist.get(v, math.inf):
                    dist[v] = nd
                    best[v] = (u, e)
                    heapq.heappush(pq, (nd, v))
        link_len = dist.get(dst, math.inf)
        if not found or link_len > old_len * MAX_DETOUR:
            # 真实图里连不通（或绕行离谱）：先试「市区前缀贴路化」，拼不出则至少
            # 把手写里程校成几何实测值（保证「声称里程 == 画线里程」）
            patched_len = urbanize_prefix(h, pos, old_geom, dist, best, idx)
            if patched_len is not None:
                h["distanceKm"] = round(patched_len, 1)
                reports.append(f"{hid} {h.get('name','')}: 真实网不可达，城区前缀段已换真实路网链"
                               f"（{len(old_geom)}点/{old_len:.1f}km -> {len(h['c'])}点/{patched_len:.1f}km，"
                               f"最大跳点{max_gap_m(h['c']):.0f}m），里程校正 -> {h['distanceKm']}km")
                continue
            h["distanceKm"] = round(old_len, 1)
            reports.append(f"{hid}: 真实网不可达或绕行过大（实测 {link_len:.1f}km vs 上限 {old_len * MAX_DETOUR:.1f}km），"
                           f"里程按几何实测校正 -> {h['distanceKm']}km")
            continue
        # 回溯路径并拼接真实几何
        chain, u = [], dst
        while u != src:
            pu, e = best[u]
            chain.append((pu, u, e))
            u = pu
        chain.reverse()
        new_geom = [pos[src]]
        off_corridor = False
        for _, v, e in chain:
            g = edge_geom(e, pos)
            # 方向对齐：让 g 的起点接在当前折线末端
            if haversine_km(g[0], new_geom[-1]) > haversine_km(g[-1], new_geom[-1]):
                g = g[::-1]
            for p in g[1:]:
                # 偏离原走廊太远的顶点说明走错走廊，宁可不贴路
                if not idx.within(p, MAX_OFF_CORRIDOR_KM):
                    off_corridor = True
                    break
                if haversine_km(new_geom[-1], p) < 1e-7:
                    continue
                new_geom.append(p)
            if off_corridor:
                break
            # 边几何与节点不重合时按 toCoords 同口径收口：<50m 吸附到节点，否则显式补一跳
            dnode = haversine_km(new_geom[-1], pos[v])
            if dnode > 1e-7:
                if dnode < 0.05:
                    new_geom[-1] = pos[v]
                else:
                    new_geom.append(pos[v])
        if off_corridor:
            h["distanceKm"] = round(old_len, 1)
            reports.append(f"{hid}: 贴路链偏离原走廊 >{MAX_OFF_CORRIDOR_KM:.0f}km，放弃改写，"
                           f"里程按几何实测校正 -> {h['distanceKm']}km")
            continue
        if haversine_km(new_geom[-1], pos[dst]) > 1e-7:
            new_geom.append(pos[dst])
        new_len = poly_len_km(new_geom)
        old_gap = max_gap_m(old_geom)
        new_gap = max_gap_m(new_geom)
        h["c"] = new_geom
        h["distanceKm"] = round(new_len, 1)
        reports.append(f"{hid} {h.get('name','')}: {len(old_geom)}点/{old_len:.1f}km(最大跳点{old_gap:.0f}m) -> "
                       f"{len(new_geom)}点/{new_len:.1f}km(最大跳点{new_gap:.0f}m)，经 {len(chain)} 条真实边")
    return reports


def main():
    dry = "--dry-run" in sys.argv
    with open(NETWORK, encoding="utf-8") as f:
        data = json.load(f)
    for line in refine_corridors(data["nodes"], data["edges"]):
        print(line)
    if dry:
        print("[dry-run] 未写文件")
        return
    import shutil
    bak = NETWORK + ".pre-corridor.bak"
    if not os.path.exists(bak):
        shutil.copy2(NETWORK, bak)
        print(f"已备份原路网 -> {bak}")
    with open(NETWORK, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(f"已写回: {NETWORK}")


if __name__ == "__main__":
    main()
