# -*- coding: utf-8 -*-
"""从越南 PBF 提取地名节点（place=city/town/village），合并进路网 JSON 作为标注节点。

用法: cd tools && python extract_place_names.py [--include-villages]
输出: 直接在 src/main/resources/data/vietnam-road-network.json 的 nodes 末尾追加
      （id 加 "P-" 前缀避免与现有节点冲突，后端 Dijkstra 不受影响——新增节点无边连接）
"""
import argparse
import io
import json
import os
import sys

import osmium

HERE = os.path.dirname(os.path.abspath(__file__))
PBF = os.path.join(HERE, "data", "vietnam-260811.osm.pbf")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")

ACCEPT = {"city", "town", "village"}


class PlaceHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.places = []

    def node(self, n):
        if not n.tags:
            return
        place = n.tags.get("place")
        if place not in ACCEPT:
            return
        name = n.tags.get("name")
        if not name:
            return
        self.places.append({
            "id": "P-" + str(n.id),
            "name": name,
            "latitude": n.location.lat,
            "longitude": n.location.lon,
            "type": place,
        })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--include-villages", action="store_true", help="包含 village 级地名（数量大，默认不含）")
    args = parser.parse_args()

    if not os.path.exists(PBF):
        print(f"[错误] 未找到 PBF 文件: {PBF}")
        sys.exit(1)

    print("正在流式读取 PBF 提取地名节点…")
    handler = PlaceHandler()
    handler.apply_file(PBF)
    places = handler.places
    if not args.include_villages:
        places = [p for p in places if p["type"] in ("city", "town")]
    print(f"提取地名: 共 {len(handler.places)}，采用 {len(places)} (city/town)")

    d = json.load(io.open(OUT, encoding="utf-8"))
    existing = {n["id"] for n in d["nodes"]}
    added = 0
    for p in places:
        if p["id"] in existing:
            continue
        d["nodes"].append(p)
        added += 1

    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False)
    print(f"合并完成: 新增 {added} 个地名节点，总节点数 {len(d['nodes'])}")
    # 写版本标记（避免异步环境误判脚本未生效）
    with open(os.path.join(HERE, "place_extract_version.txt"), "w", encoding="utf-8") as f:
        f.write(f"places={len(places)} added={added} total_nodes={len(d['nodes'])}\n")


if __name__ == "__main__":
    main()
