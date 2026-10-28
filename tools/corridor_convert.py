# -*- coding: utf-8 -*-
"""
corridor_convert.py —— 走廊驱动的路网生成引擎（第二走廊可行性验证的落地件）

为什么要单独有这个文件：convert_vietnam_pbf.py 的图抽取逻辑（generate_source）本来就是
参数化的，但它的**编排**（main）把「读哪几份 PBF、走廊 bbox、哪些锚点、哪些手写边」写死在
模块常量里。于是「换国家」实际等于「改代码」，主张就没法验证。本模块把这些编排参数搬进
tools/corridors/<name>.json，流程只认配置：

    python tools/corridor_convert.py --corridor vietnam-cn          # 与旧版同一条走廊
    python tools/corridor_convert.py --corridor guangxi-cn          # 只用广西 extract 的第二走廊
    python tools/corridor_convert.py --corridor thailand            # 缺 PBF 时明确回报缺什么
    python tools/corridor_convert.py --dump-corridor vietnam-cn     # 把旧版常量导出成配置
    python tools/corridor_convert.py --check-corridor vietnam-cn    # 配置与旧版常量逐项对账

图抽取本体不在这里重写，而是 import convert_vietnam_pbf 的 generate_source —— 只有一份实现，
换走廊换的是数据与配置，不是引擎代码。
"""
import json
import os
import sys
from collections import deque

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import convert_vietnam_pbf as cv  # noqa: E402  复用同一套图抽取实现与常量

CORRIDOR_DIR = os.path.join(_HERE, "corridors")
DEFAULT_CORRIDOR = "vietnam-cn"


def builtin_vietnam_cn_spec():
    """改造前 convert_vietnam_pbf.main() 的行为（全部取自它的模块常量）。

    这份 spec 与 corridors/vietnam-cn.json 必须逐项相等，--check-corridor 就是干这个的：
    零回归不靠口头声明「我只是重构没改逻辑」。
    """
    return {
        "id": DEFAULT_CORRIDOR,
        "label": "中国广西—越北跨境走廊",
        "note": "缺省走廊。由 --dump-corridor 从 convert_vietnam_pbf.py 的内置常量导出，"
                "与改造前的硬编码编排逐项相等（--check-corridor 验）。",
        "bbox": dict(cv.BBOX),
        "sources": [
            {"pbf": _rel(cv.PBF), "label": "越南", "anchors": dict(cv.ANCHOR_CITIES),
             "snapKm": 6.0, "levels": sorted(cv.LEVELS), "idPrefix": ""},
            {"pbf": _rel(cv.PBF_CN), "label": "中国广西", "anchors": dict(cv.CN_ANCHOR_CITIES),
             "snapKm": 20.0, "levels": sorted(cv.LEVELS_CN), "idPrefix": "C"},
        ],
        "manualNodes": cv.CN_NODES,
        "manualEdges": cv.CN_EDGES,
        "manualEdgeGeom": cv.CN_ROAD_GEOM,
        "refine": True,
        "connectivity": ["NN", "HN"],
        "out": _rel(cv.OUT),
    }


def _rel(path):
    """路径一律按 tools/ 相对写法存进配置，避免把某台机器的绝对目录写死进仓库。"""
    return os.path.relpath(path, _HERE).replace("\\", "/")


def _jsonable(obj):
    """代码常量里的 tuple 过一遍 JSON 会变成 list，两边都走这个函数才有可比性。"""
    return json.loads(json.dumps(obj, ensure_ascii=False))


def _canon(obj):
    """对账用的全量归一：set → 有序 list，tuple → list，递归到底。
    配置那边 load 完是 set/tuple，内置常量那边也是，不能只靠 JSON 走一圈。"""
    if isinstance(obj, set):
        return sorted(_canon(x) for x in obj)
    if isinstance(obj, tuple):
        return [_canon(x) for x in obj]
    if isinstance(obj, dict):
        return {k: _canon(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_canon(x) for x in obj]
    return obj


def dump_corridor(name, spec=None):
    spec = builtin_vietnam_cn_spec() if spec is None else spec
    os.makedirs(CORRIDOR_DIR, exist_ok=True)
    path = os.path.join(CORRIDOR_DIR, name + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(_jsonable(spec), f, ensure_ascii=False, indent=2)
        f.write("\n")
    return path


def load_corridor(name):
    path = os.path.join(CORRIDOR_DIR, name + ".json")
    if not os.path.exists(path):
        avail = sorted(os.path.splitext(x)[0] for x in os.listdir(CORRIDOR_DIR)) if os.path.isdir(CORRIDOR_DIR) else []
        raise SystemExit("[错误] 未找到走廊配置 %s；可用走廊：%s" % (path, ", ".join(avail) or "（无）"))
    with open(path, "r", encoding="utf-8") as f:
        spec = json.load(f)
    for key in ("id", "label", "bbox", "sources", "out"):
        if key not in spec:
            raise SystemExit("[错误] 走廊配置 %s 缺字段 %s" % (name, key))
    if not spec["sources"]:
        raise SystemExit("[错误] 走廊 %s 没有任何数据源，跑不出路网" % name)
    for s in spec["sources"]:
        # anchors 的 [名, 纬, 经] 读成三元组、levels 读成集合，与旧常量的类型一致
        s["anchors"] = {k: (v[0], float(v[1]), float(v[2])) for k, v in s["anchors"].items()}
        s["levels"] = set(s["levels"])
        s["pbf"] = os.path.join(_HERE, s["pbf"]) if not os.path.isabs(s["pbf"]) else s["pbf"]
        s.setdefault("idPrefix", "")
    spec.setdefault("manualNodes", [])
    spec.setdefault("manualEdges", [])
    spec.setdefault("manualEdgeGeom", {})
    spec.setdefault("refine", False)
    spec.setdefault("connectivity", None)
    return spec


def corridor_diff(name):
    """配置文件与内置常量的逐项差异；空列表即等价。"""
    a = _canon(load_corridor(name))
    b = _canon(builtin_vietnam_cn_spec())
    for src in a["sources"]:
        src["pbf"] = os.path.normpath(src["pbf"])
    for src in b["sources"]:
        # 内置侧存的是 tools/ 相对路径，与 load 后的绝对路径同口径才能比
        src["pbf"] = os.path.normpath(os.path.join(_HERE, src["pbf"]))
    diffs = []
    for key in sorted(set(a) | set(b)):
        if a.get(key) != b.get(key):
            diffs.append("%s: 配置=%s 内置=%s" % (key, _brief(a.get(key)), _brief(b.get(key))))
    return diffs


def _brief(v):
    import pprint
    return " ".join(pprint.pformat(v, width=200).split())[:150]


def build(spec, bbox=None):
    """按走廊配置生成 (nodes, edges)。与旧版 main() 同一套合并/前缀/精修规则。"""
    bbox = bbox or dict(spec["bbox"])
    all_nodes, all_edges, snapped = [], [], set()
    primary_diag = None
    for idx, s in enumerate(spec["sources"]):
        if not os.path.exists(s["pbf"]):
            # 输入缺失时的回报就是可行性结论的一部分：要说清缺哪个文件、去哪儿拿、引擎不用改
            raise SystemExit("[错误] 走廊 %s 的输入 PBF 不存在：%s\n"
                             "       把 OSM extract（Geofabrik 对应国家/地区的 -latest.osm.pbf）放到 tools/data/ 下重跑即可，"
                             "引擎与配置都不需要改。" % (spec["id"], s["pbf"]))
        n, e, am, deg, coords, ways, keys = cv.generate_source(
            [s["pbf"]], bbox, s["anchors"], float(s["snapKm"]), s["label"], levels=s["levels"])
        pfx = s.get("idPrefix") or ""
        if pfx:
            # 前缀数字节点/边 ID，规避两份 extract 在边境重叠带撞号（同一条路两侧都有）
            for node in n:
                if node["id"].isdigit():
                    node["id"] = pfx + node["id"]
            for edge in e:
                edge["id"] = pfx + edge["id"]
                if edge["fromNodeId"].isdigit():
                    edge["fromNodeId"] = pfx + edge["fromNodeId"]
                if edge["toNodeId"].isdigit():
                    edge["toNodeId"] = pfx + edge["toNodeId"]
        all_nodes.append(n)
        all_edges.append(e)
        snapped |= set(am)
        if idx == 0:
            primary_diag = (am, deg, coords, ways, keys)

    # 未吸附的手写节点作兜底；手写走廊/口岸边保证走廊一定连通，密集真实路网叠加其上
    fallback = [x for x in spec["manualNodes"] if x["id"] not in snapped]
    nodes = fallback + [x for src in all_nodes for x in src]
    edges = list(spec["manualEdges"]) + [x for src in all_edges for x in src]
    for e in edges:
        if e.get("c") is None and e["id"] in spec["manualEdgeGeom"]:
            e["c"] = spec["manualEdgeGeom"][e["id"]]

    if spec["refine"]:
        from refine_cn_corridors import refine_corridors
        for line in refine_corridors(nodes, edges):
            print("[走廊精修] %s" % line)

    return nodes, edges, primary_diag


def report(nodes, edges, spec, primary_diag):
    """连通性与锚点接入自检：新走廊最容易出的问题是锚点没吸到真实路网上。"""
    adj = {}
    for e in edges:
        adj.setdefault(e["fromNodeId"], set()).add(e["toNodeId"])
        adj.setdefault(e["toNodeId"], set()).add(e["fromNodeId"])
    if spec["connectivity"]:
        a, b = spec["connectivity"]
        seen, q = {a}, deque([a])
        while q:
            for nx in adj.get(q.popleft(), ()):
                if nx not in seen:
                    seen.add(nx)
                    q.append(nx)
        print("[连通自检] %s→%s %s（%s 侧连通节点 %d）"
              % (a, b, "可达" if b in seen else "!!! 不可达 !!!", a, len(seen)))
    edge_nodes = set()
    for e in edges:
        edge_nodes.add(e["fromNodeId"])
        edge_nodes.add(e["toNodeId"])
    not_wired = []
    for s in spec["sources"]:
        for cid in s["anchors"]:
            if cid not in edge_nodes:
                not_wired.append("%s(%s)" % (cid, s["anchors"][cid][0]))
    print("[锚点接入] 未接入路网的锚点 %d 个%s" % (len(not_wired), ("：" + ", ".join(not_wired)) if not_wired else ""))
    if primary_diag:
        am = primary_diag[0]
        print("[主源吸附] 命中锚点 %d / 声明锚点 %d" % (len(am), len(spec["sources"][0]["anchors"])))
    return not_wired


def run(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="走廊驱动的路网生成引擎（换走廊=换配置+换 PBF，不改代码）")
    parser.add_argument("--corridor", default=DEFAULT_CORRIDOR,
                        help="走廊名，对应 tools/corridors/<name>.json；缺省 vietnam-cn")
    parser.add_argument("--bbox", default=None, help="覆盖走廊 bbox: lat_min,lat_max,lon_min,lon_max")
    parser.add_argument("--out", default=None, help="输出 JSON 路径（默认取走廊配置里的 out）")
    parser.add_argument("--dump-corridor", metavar="NAME", default=None,
                        help="把 convert_vietnam_pbf.py 的内置常量导出为该名字的走廊配置后退出")
    parser.add_argument("--check-corridor", metavar="NAME", nargs="?", const=DEFAULT_CORRIDOR, default=None,
                        help="校验走廊配置与内置常量逐项相等后退出；不写名字就查缺省走廊")
    args = parser.parse_args(argv)

    if args.dump_corridor:
        print("已导出走廊配置 -> %s" % dump_corridor(args.dump_corridor))
        return 0
    if args.check_corridor:
        check_name = args.check_corridor
        diffs = corridor_diff(check_name)
        for line in diffs:
            print("  [差异] %s" % line)
        print("[走廊对账] %s 与内置常量：%s" % (check_name, "逐项相等 PASS" if not diffs else "FAIL（%d 项）" % len(diffs)))
        return 0 if not diffs else 1

    spec = load_corridor(args.corridor)
    bbox = dict(spec["bbox"])
    if args.bbox:
        p = [float(x) for x in args.bbox.split(",")]
        bbox = {"lat_min": p[0], "lat_max": p[1], "lon_min": p[2], "lon_max": p[3]}
        print("覆盖 bbox: %s" % bbox)
    out_path = args.out or os.path.normpath(os.path.join(_HERE, spec["out"]))

    print("走廊: %s（%s） 数据源 %d 份" % (spec["id"], spec["label"], len(spec["sources"])))
    nodes, edges, primary_diag = build(spec, bbox)
    report(nodes, edges, spec, primary_diag)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"nodes": nodes, "edges": edges}, f, ensure_ascii=False, separators=(",", ":"))
    print("已写出: %s" % out_path)
    print("  节点 %d / 边 %d" % (len(nodes), len(edges)))
    return 0


if __name__ == "__main__":
    sys.exit(run())
