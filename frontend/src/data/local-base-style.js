// 自研本地矢量底图样式（OpenMapTiles schema，对应 planetiler OpenMapTilesProfile 输出）。
// 数据源指向后端静态映射 /tiles/{z}/{x}/{y}.pbf，完全离线、无第三方在线依赖。
// 注意：MapLibre 在 Web Worker 中加载瓦片，Worker 没有文档基址，相对 URL 无法解析
// （new Request('/tiles/...') 抛 "Failed to parse URL"）→ 必须拼成绝对 URL。
const TILE_BASE = (typeof location !== 'undefined' && location.origin) ? location.origin : ''
export default {
  version: 8,
  sources: {
    local: {
      type: 'vector',
      tiles: [`${TILE_BASE}/tiles/{z}/{x}/{y}.pbf`],
      maxzoom: 14
    }
  },
  layers: [
    { id: 'background', type: 'background', paint: { 'background-color': '#f3efe6' } },
    // ---- 水系 ----
    {
      id: 'water',
      type: 'fill',
      source: 'local',
      'source-layer': 'water',
      filter: ['all', ['!=', 'brunnel', 'tunnel']],
      paint: { 'fill-color': '#a5c8e8', 'fill-outline-color': '#8fb6d8' }
    },
    {
      id: 'waterway-line',
      type: 'line',
      source: 'local',
      'source-layer': 'waterway',
      filter: ['all', ['in', 'class', 'river', 'canal'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#a5c8e8',
        'line-width': ['interpolate', ['linear'], ['zoom'], 6, 0.4, 12, 1.6, 16, 3.5]
      }
    },
    // ---- 地表覆盖 ----
    {
      id: 'landcover-wood',
      type: 'fill',
      source: 'local',
      'source-layer': 'landcover',
      filter: ['in', 'class', 'wood', 'forest'],
      paint: { 'fill-color': '#cfe0c2', 'fill-opacity': 0.7 }
    },
    {
      id: 'landcover-grass',
      type: 'fill',
      source: 'local',
      'source-layer': 'landcover',
      filter: ['in', 'class', 'grass', 'scrub'],
      paint: { 'fill-color': '#d6e8c8', 'fill-opacity': 0.7 }
    },
    {
      id: 'landuse-residential',
      type: 'fill',
      source: 'local',
      'source-layer': 'landuse',
      filter: ['==', 'class', 'residential'],
      paint: { 'fill-color': '#e9e2d6' }
    },
    {
      id: 'landuse-commercial',
      type: 'fill',
      source: 'local',
      'source-layer': 'landuse',
      filter: ['in', 'class', 'commercial', 'industrial'],
      paint: { 'fill-color': '#e4dfd8' }
    },
    {
      id: 'park',
      type: 'fill',
      source: 'local',
      'source-layer': 'park',
      paint: { 'fill-color': '#cfe6bd' }
    },
    // ---- 行政区划边界 ----
    {
      id: 'boundary-admin2',
      type: 'line',
      source: 'local',
      'source-layer': 'boundary',
      filter: ['==', 'admin_level', 2],
      paint: {
        'line-color': '#8a5a3c',
        'line-width': 1.4,
        'line-dasharray': [3, 2]
      }
    },
    {
      id: 'boundary-admin4',
      type: 'line',
      source: 'local',
      'source-layer': 'boundary',
      filter: ['<=', 'admin_level', 4],
      paint: {
        'line-color': '#a58a78',
        'line-width': 0.8,
        'line-dasharray': [2, 2],
        'line-opacity': 0.8
      }
    },
    {
      id: 'boundary-admin-low',
      type: 'line',
      source: 'local',
      'source-layer': 'boundary',
      filter: ['all', ['>=', 'admin_level', 5], ['<=', 'admin_level', 8]],
      paint: {
        'line-color': '#c0b3a6',
        'line-width': 0.5,
        'line-dasharray': [1.5, 2],
        'line-opacity': 0.7
      }
    },
    // ---- 隧道（虚线） ----
    {
      id: 'tunnel',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['==', 'brunnel', 'tunnel'],
      paint: {
        'line-color': '#d6d0c4',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1, 12, 3, 16, 6],
        'line-dasharray': [2, 1.6],
        'line-opacity': 0.8
      }
    },
    // ---- 道路：按等级分色 ----
    {
      id: 'highway-minor',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['!in', 'class', 'motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'rail'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#f7f3ea',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 0.6, 12, 2, 16, 5],
        'line-opacity': 0.9
      }
    },
    {
      id: 'highway-tertiary',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['==', 'class', 'tertiary'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#f2d98a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1, 12, 3.5, 16, 7]
      }
    },
    {
      id: 'highway-secondary',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['==', 'class', 'secondary'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#eec65a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.2, 12, 4, 16, 8]
      }
    },
    {
      id: 'highway-primary',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['==', 'class', 'primary'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#e98f3a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.4, 12, 4.5, 16, 9]
      }
    },
    {
      id: 'highway-trunk',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['==', 'class', 'trunk'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#d96a2a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.6, 12, 5, 16, 10]
      }
    },
    {
      id: 'highway-motorway',
      type: 'line',
      source: 'local',
      'source-layer': 'transportation',
      filter: ['all', ['==', 'class', 'motorway'], ['!=', 'brunnel', 'tunnel']],
      paint: {
        'line-color': '#c84545',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.8, 12, 5.5, 16, 11]
      }
    },
    // ---- 建筑 ----
    {
      id: 'building',
      type: 'fill',
      source: 'local',
      'source-layer': 'building',
      minzoom: 13,
      paint: { 'fill-color': '#dcd4c8', 'fill-opacity': 0.9, 'fill-outline-color': '#c9bfb0' }
    }
    // 地名标注由 Leaflet 中文标注层（zhPlaces）补充，无需 glyphs 字体服务
  ]
};
