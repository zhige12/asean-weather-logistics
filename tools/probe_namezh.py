# -*- coding: utf-8 -*-
"""探测 PBF 中地名节点及中文译名（name:zh）覆盖情况（临时脚本）"""
import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import osmium

HERE = os.path.dirname(os.path.abspath(__file__))
PBF = os.path.join(HERE, "data", "vietnam-260811.osm.pbf")

ACCEPT = {"city", "town"}


class Probe(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.total = 0
        self.with_name = 0
        self.with_zh = 0
        self.samples = []

    def node(self, n):
        if not n.tags:
            return
        place = n.tags.get("place")
        if place not in ACCEPT:
            return
        self.total += 1
        name = n.tags.get("name")
        zh = n.tags.get("name:zh")
        if name:
            self.with_name += 1
        if zh:
            self.with_zh += 1
            if len(self.samples) < 20:
                self.samples.append((name, zh, n.location.lat, n.location.lon))


def main():
    print("PBF:", PBF)
    p = Probe()
    p.apply_file(PBF)
    print(f"place=city/town 节点总数: {p.total}")
    print(f"带 name 的: {p.with_name}  |  带 name:zh 的: {p.with_zh}")
    if p.with_zh:
        print("样例 (越南语 → 中文):")
        for name, zh, lat, lon in p.samples:
            print(f"  {name} -> {zh}  ({lat:.4f}, {lon:.4f})")


if __name__ == "__main__":
    main()
