# -*- coding: utf-8 -*-
"""给路网地名节点打行政级档位 level，实现地图缩放时"国省→市→县镇"逐级注记。

分级（对应前端 MapView.vue 的显示阈值与字号）:
  level 1  首都 / 直辖市 / 重点边境城市（最小缩放即显示，字号最大）
  level 2  地级市（city 其余）
  level 3  县 / 镇（town）

幂等：节点已带有效 level 则跳过。用法: cd tools && python level_places.py
"""
import io
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")

# 首都/直辖市/重点城市白名单（兼容 中文 / 越南语 / 英文 写法）
LEVEL1_NAMES = {
    # 越南：首都 + 直辖市
    "河内", "Hà Nội", "Hanoi",
    "胡志明", "Thành phố Hồ Chí Minh", "Hồ Chí Minh", "Saigon", "Ho Chi Minh",
    "海防", "Hải Phòng", "Haiphong",
    "岘港", "Đà Nẵng", "Da Nang",
    "芹苴", "Cần Thơ", "Can Tho",
    # 中国侧边境/首府（演示起点侧）
    "南宁", "Nanning",
    "凭祥", "Pingxiang",
    "东兴", "Dongxing",
    "河口", "Hekou",
    "崇左", "Chongzuo",
    # 越南知名省辖市/旅游名城（演示常用）
    "下龙", "Hạ Long", "Ha Long",
    "老街", "Lào Cai", "Lao Cai",
    "谅山", "Lạng Sơn", "Lang Son",
    "芒街", "Móng Cái", "Mong Cai",
    "北江", "Bắc Giang", "Bac Giang",
    "太原", "Thái Nguyên", "Thai Nguyen",
    "清化", "Thanh Hóa", "Thanh Hoa",
    "荣市", "Vinh",
    "顺化", "Huế", "Hue",
    "芽庄", "Nha Trang",
    "大叻", "Đà Lạt", "Da Lat",
}


def main():
    d = json.load(io.open(OUT, encoding="utf-8"))
    stats = {1: 0, 2: 0, 3: 0, "skipped": 0}
    changed = 0
    for n in d["nodes"]:
        if n.get("level") in (1, 2, 3):
            stats["skipped"] += 1
            continue
        t = n.get("type")
        if t not in ("city", "town"):
            continue
        name = n.get("name", "")
        if t == "town":
            lv = 3
        elif name in LEVEL1_NAMES:
            lv = 1
        else:
            lv = 2
        n["level"] = lv
        stats[lv] += 1
        changed += 1

    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False)
    print(f"分级完成: 新增 level {changed} 个 | L1={stats[1]} L2={stats[2]} L3={stats[3]} 跳过={stats['skipped']}")
    print(f"总节点 {len(d['nodes'])}")


if __name__ == "__main__":
    main()
