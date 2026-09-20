# -*- coding: utf-8 -*-
"""
build_cn_road_geom.py
解码 tools/data/cn_shapes.txt 中的 Valhalla polyline6 字符串，
校验起终点匹配中国侧锚点城市，生成新的 CN_ROAD_GEOM 字典，
并直接替换 tools/convert_vietnam_pbf.py 中对应常量。

输入: tools/data/cn_shapes.txt (格式: Sxxx<TAB>polyline6...，其中 \\ 已转义为 <BS>)
输出: 更新 convert_vietnam_pbf.py 的 CN_ROAD_GEOM dict
"""
import math
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
SHAPES_TXT = os.path.join(_HERE, "data", "cn_shapes.txt")
TARGET_PY = os.path.join(_HERE, "convert_vietnam_pbf.py")

# Sxxx -> (edge_id, from_node, to_node)
SHAPE_MAP = {
    "S1": ("E1", "NN", "CZ"),
    "S2": ("E2", "CZ", "PX"),
    "S3": ("E3", "PX", "YGG"),
    "S4": ("E4", "YGG", "DD"),
    "S5": ("E8", "NN", "DX"),
}

# 节点 -> (lat, lon) — 与 convert_vietnam_pbf.py 中 CN_NODES 保持一致
NODES = {
    "NN":  (22.8170, 108.3665),
    "CZ":  (22.3765, 107.3640),
    "PX":  (22.1040, 106.7500),
    "YGG": (22.0200, 106.7130),
    "DX":  (21.5490, 107.9660),
    "DD":  (21.9900, 106.6850),
    "HK":  (22.5070, 103.9580),
    "MC":  (21.5270, 107.9660),
    "LC":  (22.4854, 103.9756),
}

R_EARTH = 6371.0


def haversine_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.asin(math.sqrt(a))


def decode_polyline6(s):
    """Valhalla polyline6 解码：精度 1e-6。"""
    coords = []
    index = 0
    lat = 0
    lon = 0
    factor = 1e6
    length = len(s)
    while index < length:
        # latitude delta
        shift = 0
        result = 0
        while True:
            if index >= length:
                raise ValueError("polyline 截断：纬度 varint 不完整")
            b = ord(s[index]) - 63
            index += 1
            result |= (b & 0x1f) << shift
            shift += 5
            if b < 0x20:
                break
        dlat = ~(result >> 1) if result & 1 else result >> 1
        lat += dlat
        # longitude delta
        shift = 0
        result = 0
        while True:
            if index >= length:
                raise ValueError("polyline 截断：经度 varint 不完整")
            b = ord(s[index]) - 63
            index += 1
            result |= (b & 0x1f) << shift
            shift += 5
            if b < 0x20:
                break
        dlon = ~(result >> 1) if result & 1 else result >> 1
        lon += dlon
        coords.append((lat / factor, lon / factor))
    return coords


def read_shapes(path):
    """返回 {shape_id: polyline_string}"""
    out = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip():
                continue
            parts = line.split("\t", 1)
            if len(parts) != 2:
                continue
            sid, body = parts
            # 把 <BS> 还原成 \
            body = body.replace("<BS>", "\\")
            out[sid.strip()] = body
    return out


def format_geom_value(pts, per_line=10):
    """生成 [[lat,lon], ...] 字符串，每行 per_line 个点，便于阅读"""
    flat = ", ".join(f"[{la}, {lo}]" for la, lo in pts)
    # 在 flat 串里按 per_line 个一组插入换行
    tokens = []
    i = 0
    n = len(pts)
    while i < n:
        j = min(i + per_line, n)
        tokens.append(", ".join(f"[{pts[k][0]}, {pts[k][1]}]" for k in range(i, j)))
        i = j
    inner = ",\n            ".join(tokens)
    return "[" + inner + "]"


def main():
    print("读取 shapes:", SHAPES_TXT)
    if not os.path.exists(SHAPES_TXT):
        print(f"  [错误] 文件不存在: {SHAPES_TXT}")
        sys.exit(1)
    shapes = read_shapes(SHAPES_TXT)
    print(f"  找到 {len(shapes)} 条: {sorted(shapes.keys())}")

    new_geom = {}
    failed = []
    for sid, (eid, fn, tn) in SHAPE_MAP.items():
        if sid not in shapes:
            print(f"  [跳过] {sid} -> {eid} ({fn}->{tn})：未在文件中")
            continue
        try:
            coords = decode_polyline6(shapes[sid])
        except Exception as ex:
            print(f"  [失败] {sid}：解码异常 {ex}")
            failed.append(sid)
            continue
        if not coords:
            print(f"  [失败] {sid}：解码为空")
            failed.append(sid)
            continue
        # 校验起终点
        first = coords[0]
        last = coords[-1]
        exp_first = NODES[fn]
        exp_last = NODES[tn]
        d_start = haversine_km(first[0], first[1], exp_first[0], exp_first[1])
        d_end = haversine_km(last[0], last[1], exp_last[0], exp_last[1])
        # 总路径长度
        total_km = sum(
            haversine_km(coords[i][0], coords[i][1], coords[i + 1][0], coords[i + 1][1])
            for i in range(len(coords) - 1)
        )
        status = "OK"
        if d_start > 2.0 or d_end > 2.0:
            status = f"WARN(起{d_start:.2f}km, 终{d_end:.2f}km)"
        print(f"  {sid} -> {eid} ({fn}->{tn})  点数={len(coords):4d}  "
              f"全长≈{total_km:6.1f}km  起偏{d_start:.2f}km 终偏{d_end:.2f}km  [{status}]")
        # 写入 dict 时转为 [lat, lon] 列表（保留原始精度）
        new_geom[eid] = [[round(lat, 6), round(lon, 6)] for lat, lon in coords]

    if failed:
        print(f"\n[警告] 失败的 shape: {failed}")

    if not new_geom:
        print("\n无新几何可注入，退出")
        return

    # 输出新 CN_ROAD_GEOM 的源码
    out_lines = ["CN_ROAD_GEOM = {"]
    items = list(sorted(new_geom.keys()))
    for idx, eid in enumerate(items):
        pts = new_geom[eid]
        items_str = format_geom_value(pts, per_line=8)
        comma = "," if idx < len(items) - 1 else ""
        out_lines.append(f'    "{eid}": {items_str}{comma}')
    out_lines.append("}")
    new_block = "\n".join(out_lines)

    print("\n=== 替换目标文件中的 CN_ROAD_GEOM ===")
    print(f"  目标: {TARGET_PY}")
    with open(TARGET_PY, "r", encoding="utf-8") as f:
        src = f.read()
    # 匹配从 "CN_ROAD_GEOM = {" 开始到下一个独立的 "}" 结束（同行 + 多行都行）
    pattern = re.compile(r'CN_ROAD_GEOM\s*=\s*\{[^}]*\}', re.MULTILINE | re.DOTALL)
    new_src, n = pattern.subn(new_block, src, count=1)
    if n != 1:
        print(f"  [失败] 未匹配到 CN_ROAD_GEOM 定义 (n={n})")
        sys.exit(1)
    with open(TARGET_PY, "w", encoding="utf-8") as f:
        f.write(new_src)
    print(f"  已写入 {len(new_geom)} 条: {sorted(new_geom.keys())}")
    print("\n下一步: cd tools && python convert_vietnam_pbf.py 重新生成 JSON")


if __name__ == "__main__":
    main()