# -*- coding: utf-8 -*-
"""最终校验：锚点可达性 + 关键路径连通性 + ID 唯一性
用法: cd tools && python verify_network.py
"""
import json
import os
from collections import deque

_HERE = os.path.dirname(os.path.abspath(__file__))
_JSON = os.path.join(_HERE, "..", "src", "main", "resources", "data", "vietnam-road-network.json")
d = json.load(open(_JSON, encoding='utf-8'))
nodes = d['nodes']
edges = d['edges']

# ID 唯一性
eids = [e['id'] for e in edges]
nids = [n['id'] for n in nodes]
print(f"节点 {len(nids)} 唯一:{len(nids) == len(set(nids))} | 边 {len(eids)} 唯一:{len(eids) == len(set(eids))}")

# 无向邻接表
adj = {}
for e in edges:
    adj.setdefault(e['fromNodeId'], set()).add(e['toNodeId'])
    adj.setdefault(e['toNodeId'], set()).add(e['fromNodeId'])


def connected(a, b):
    if a not in adj or b not in adj:
        return False
    q = deque([a])
    seen = {a}
    while q:
        x = q.popleft()
        if x == b:
            return True
        for y in adj.get(x, ()):
            if y not in seen:
                seen.add(y)
                q.append(y)
    return False


print("\n关键路径连通性:")
for a, b in [('NN', 'HN'), ('LS', 'BG'), ('BG', 'HN'), ('HL', 'HD'), ('HN', 'DN'), ('YGG', 'DD'), ('DX', 'MC'), ('HK', 'LC')]:
    print(f"  {a} -> {b}: {'可达' if connected(a, b) else '!!!不可达!!!'}")

# 全部锚点
anchors = ['DD', 'LS', 'BG', 'BN', 'HN', 'MC', 'HL', 'HD', 'HP', 'ND', 'NB',
           'TH', 'VH', 'HT', 'DQH', 'QT', 'HUE', 'DN', 'TY', 'TQ', 'YB', 'LC',
           'SPA', 'HB', 'SL', 'DB', 'NN', 'CZ', 'PX', 'YGG', 'DX', 'HK']
missing = [a for a in anchors if a not in adj]
print(f"\n锚点接入: {len(anchors) - len(missing)}/{len(anchors)} {'缺失:' + str(missing) if missing else '全部接入'}")

# HN 相关边样例
print("\nHN 相关边样例:")
for e in edges:
    if 'HN' in (e['fromNodeId'], e['toNodeId']):
        print(f"  {e['id']} {e['fromNodeId']}->{e['toNodeId']} {e['distanceKm']}km [{e['roadType']}]")
