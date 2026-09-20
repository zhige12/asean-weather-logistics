import osmnx as ox
import json

print("🔄 正在从 OSM 下载路网数据，请稍候...")
print("OSMnx 版本:", ox.__version__)

# 边界: (北纬, 南纬, 东经, 西经)
bbox = (22.3, 20.8, 107.2, 105.4)

# 尝试不同调用方式（兼容旧版和新版）
try:
    # 方式1: 传递 bbox 元组作为第一个参数
    G = ox.graph_from_bbox(bbox, network_type='drive')
except TypeError:
    try:
        # 方式2: 拆包为四个位置参数
        G = ox.graph_from_bbox(*bbox, network_type='drive')
    except TypeError:
        try:
            # 方式3: 关键字参数（旧版）
            G = ox.graph_from_bbox(north=bbox[0], south=bbox[1], east=bbox[2], west=bbox[3], network_type='drive')
        except:
            raise RuntimeError("无法调用 graph_from_bbox，请检查 OSMnx 版本")

print(f"✅ 下载完成！节点数: {len(G.nodes)}，边数: {len(G.edges)}")

# 提取节点坐标
nodes_data = {}
for node_id, data in G.nodes(data=True):
    nodes_data[str(node_id)] = {
        "lat": data['y'],
        "lon": data['x']
    }

# 提取边数据
edges_data = []
for u, v, data in G.edges(data=True):
    edges_data.append({
        "source": str(u),
        "target": str(v),
        "weight": data.get('length', 1) / 1000.0  # 米转公里
    })

road_network = {
    "nodes": nodes_data,
    "edges": edges_data
}

with open("vietnam_road_network.json", "w", encoding="utf-8") as f:
    json.dump(road_network, f, ensure_ascii=False, indent=2)

print("✅ 路网数据已保存到 vietnam_road_network.json")
print(f"   节点数: {len(nodes_data)}，边数: {len(edges_data)}")