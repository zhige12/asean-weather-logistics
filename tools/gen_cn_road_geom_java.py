# -*- coding: utf-8 -*-
"""
gen_cn_road_geom_java.py — 从 tools/data/cn_shapes.txt（Valhalla polyline6 真实道路几何）
重新生成 src/main/java/.../service/CNRoadGeom.java。

旧文件是 100m 容差抽稀的历史产物，南宁市区留下 1~5km 的长弦段：真实灾害熔断友谊关、
绕行走 E8（南宁→东兴）虚拟走廊时，导航级缩放（z16）下这些长弦直接穿楼穿湖。
本脚本用 15m 容差重抽：点数从 ~240 涨到 ~900（内存与响应体都无压力），市区贴路。

用法: python tools/gen_cn_road_geom_java.py
"""
import math
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SHAPES_TXT = os.path.join(HERE, 'data', 'cn_shapes.txt')
TARGET_JAVA = os.path.normpath(os.path.join(
    HERE, '..', 'src', 'main', 'java', 'com', 'example',
    'aseanweatherlogistics', 'service', 'CNRoadGeom.java'))

# 与 build_cn_road_geom.py 同源：shape -> edge_id
SHAPE_MAP = {'S1': 'E1', 'S2': 'E2', 'S3': 'E3', 'S4': 'E4', 'S5': 'E8'}
TOL_M = 15.0  # Douglas-Peucker 容差（米）
COS_LAT = 0.92  # 南宁~河内纬度余弦近似


def decode_polyline6(s):
    coords, index, lat, lon, factor = [], 0, 0, 0, 1e6
    length = len(s)
    while index < length:
        shift = result = 0
        while True:
            b = ord(s[index]) - 63
            index += 1
            result |= (b & 0x1F) << shift
            shift += 5
            if b < 0x20:
                break
        lat += ~(result >> 1) if result & 1 else result >> 1
        shift = result = 0
        while True:
            b = ord(s[index]) - 63
            index += 1
            result |= (b & 0x1F) << shift
            shift += 5
            if b < 0x20:
                break
        lon += ~(result >> 1) if result & 1 else result >> 1
        coords.append((lat / factor, lon / factor))
    return coords


def read_shapes(path):
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.rstrip('\r\n').split('\t', 1)
            if len(parts) == 2:
                out[parts[0].strip()] = parts[1].replace('<BS>', '\\')
    return out


def _seg_dev_m(a, b, p):
    """点 p 到线段 ab 的距离（米，平面近似）"""
    ax, ay = a[0], a[1] * COS_LAT
    bx, by = b[0], b[1] * COS_LAT
    px, py = p[0], p[1] * COS_LAT
    dx, dy = bx - ax, by - ay
    n2 = dx * dx + dy * dy
    t = 0 if n2 == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / n2))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy)) * 111320


def dp(points, tol_m):
    """迭代式 Douglas-Peucker，返回保留点序列"""
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        maxd, maxi = -1.0, -1
        for i in range(a + 1, b):
            d = _seg_dev_m(points[a], points[b], points[i])
            if d > maxd:
                maxd, maxi = d, i
        if maxd > tol_m and maxi > 0:
            keep[maxi] = True
            stack.append((a, maxi))
            stack.append((maxi, b))
    return [p for p, k in zip(points, keep) if k]


def fmt(pts):
    lines, per = [], 8
    for i in range(0, len(pts), per):
        chunk = ', '.join('{%.6f,%.6f}' % (la, lo) for la, lo in pts[i:i + per])
        lines.append('      ' + chunk + (',' if i + per < len(pts) else ''))
    return '\n'.join(lines)


def main():
    shapes = read_shapes(SHAPES_TXT)
    geom = {}
    for sid, eid in sorted(SHAPE_MAP.items()):
        if sid not in shapes:
            print('  [跳过]', sid, '->', eid)
            continue
        raw = decode_polyline6(shapes[sid])
        thin = dp(raw, TOL_M)
        # 市区段最长弦（诊断）
        mx, mi = max(((math.hypot(thin[i][0] - thin[i - 1][0],
                                 (thin[i][1] - thin[i - 1][1]) * COS_LAT) * 111320, i)
                      for i in range(1, len(thin))), default=(0, 0))
        print(f'  {sid} -> {eid}: raw={len(raw)} thin={len(thin)} 最长段={mx:.0f}m@{mi}')
        geom[eid] = thin

    body = []
    body.append('package com.example.aseanweatherlogistics.service;\n')
    body.append('import java.util.LinkedHashMap;\nimport java.util.Map;\n')
    body.append('/**\n'
                ' * 中国侧虚拟走廊的贴路几何([[lat, lon], ...], 基于 OSM 真实道路/Valhalla 抽取,\n'
                ' * 抽稀容差约 %dm)。由 tools/gen_cn_road_geom_java.py 生成, 勿手改。\n'
                ' * 容差从早期 100m 收紧到 %dm：南宁市区 1~5km 长弦在导航级缩放下会直接穿建筑物。\n'
                ' */' % (int(TOL_M), int(TOL_M)))
    body.append('public final class CNRoadGeom {\n  private CNRoadGeom() {}\n')
    body.append('  public static final Map<String, double[][]> MAP;\n  static {')
    body.append('    Map<String, double[][]> m = new LinkedHashMap<>();')
    for eid in sorted(geom):
        body.append(f'    m.put("{eid}", new double[][]{{\n{fmt(geom[eid])}\n    }});')
    body.append('    MAP = m;\n  }\n}\n')
    with open(TARGET_JAVA, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(body))
    print('已写入', TARGET_JAVA)


if __name__ == '__main__':
    main()
