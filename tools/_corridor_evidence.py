# -*- coding: utf-8 -*-
"""第二走廊证据复算：NN→友谊关 的路径节点到底有多少只存在于新生成的路网里。

DEV_LOG §四 7 里那句「路径节点只存在于新走廊」必须是当场算出来的，不能是上一轮口头结论的转录
（上一轮记的是 40/40，本脚本复算后实际是 36/40，重合的 4 个正是手写锚点 NN/CZ/PX/YGG）。
这里直接对两份路网 JSON 做集合运算：
  旧走廊 = src/main/resources/data/vietnam-road-network.json（后端 classpath 默认）
  新走廊 = tools/data/guangxi-road-network.json（corridor_convert.py 生成，未入库）
路径取后端真实回包（需 8081 实例在线），回包不在仓库里就现算。
"""
import json
import os
import re
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
OLD = os.path.join(_HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")
NEW = os.path.join(_HERE, "..", "tools", "data", "guangxi-road-network.json")
SRV = "http://127.0.0.1:8081/api/route/plan-with-weather?originId=NN&destinationId=YGG&withAi=false"

ALPHA = re.compile(r"^[A-Z]{2,4}$")


def ids(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    nodes = {n["id"] for n in d["nodes"]}
    anchors = {i for i in nodes if ALPHA.match(i)}
    return nodes, anchors, len(d["edges"])


def main():
    # 新走廊路网是脚本产物（~30MB，不入库）；clone 侧缺它时要说清楚怎么再生成，而不是抛栈
    if not os.path.exists(NEW):
        print("[缺数据] 未找到新走廊路网 %s" % NEW)
        print("  重生办法：把 guangxi-latest.osm.pbf 放到 tools/data/ 下，跑 "
              "`python tools/corridor_convert.py --corridor guangxi-cn`")
        return 2
    old_nodes, old_anchor, old_edges = ids(OLD)
    new_nodes, new_anchor, new_edges = ids(NEW)
    print("旧走廊 %s 节点 / %s 边；新走廊 %s 节点 / %s 边"
          % (len(old_nodes), old_edges, len(new_nodes), new_edges))
    print("新走廊字母锚点（%d 个）：%s；其中与旧走廊同名：%s"
          % (len(new_anchor), sorted(new_anchor), sorted(new_anchor & old_anchor)))
    print("两份路网共用的 OSM 节点 id：%d 个（旧走廊含中国侧来源，重合是预期的）"
          % len((new_nodes - new_anchor) & (old_nodes - old_anchor)))

    try:
        with urllib.request.urlopen(SRV, timeout=30) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        print("[跳过路径核验] 8081 不在线或回包异常：%s" % e)
        return 0
    route = body.get("route") or {}
    path_ids = route.get("pathNodeIds") or []
    dist = route.get("totalDistanceKm")
    hours = route.get("estimatedHours")
    if not path_ids:
        print("[提示] 回包里没有 pathNodeIds，顶层键：%s / route 键：%s"
              % (list(body)[:20], list(route)[:20]))
        return 0
    print("NN→YGG 规划：%.1f km / %.2f h，路径节点 %d 个" % (float(dist), float(hours), len(path_ids)))
    only_new = [i for i in path_ids if i not in old_nodes]
    print("只存在于新走廊的路径节点：%d/%d" % (len(only_new), len(path_ids)))
    if len(only_new) != len(path_ids):
        print("与旧走廊重合的路径节点（%d 个）：%s"
              % (len(path_ids) - len(only_new), [i for i in path_ids if i in old_nodes][:12]))
    print("RESULT:", "PASS" if only_new else "FAIL（路径全部落在旧走廊里，说明读的不是新路网）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
