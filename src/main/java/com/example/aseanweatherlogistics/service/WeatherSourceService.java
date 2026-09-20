package com.example.aseanweatherlogistics.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 气象数据源开关（内存态，运行时可切换）：
 * 比赛官方接口 ⇄ 公网真实气象接口 ⇄ 离线模拟数据。
 * <p>
 * 默认来源由配置 weather.source 指定，缺省为比赛官方接口（比赛主链路）。
 */
@Service
public class WeatherSourceService {

    public static final String CONTEST = "contest-observation";
    public static final String OPEN_METEO = "open-meteo";
    public static final String SIMULATED = "simulated";

    private final Map<String, WeatherProvider> providers = new LinkedHashMap<>();
    private volatile String source;

    public WeatherSourceService(List<WeatherProvider> all,
                                @Value("${weather.source:contest-observation}") String defaultSource) {
        for (WeatherProvider p : all) {
            providers.put(p.id(), p);
        }
        this.source = providers.containsKey(defaultSource) ? defaultSource : CONTEST;
    }

    /** 当前选中的数据源标识 */
    public String source() {
        return source;
    }

    /** 当前数据源实现（未找到返回 null，由上层降级） */
    public WeatherProvider current() {
        return providers.get(source);
    }

    /** 切换数据源；未知标识返回 false */
    public boolean setSource(String id) {
        if (id == null || !providers.containsKey(id)) {
            return false;
        }
        source = id;
        return true;
    }

    /** 数据源展示名（供前端/日志） */
    public String labelOf(String id) {
        WeatherProvider p = providers.get(id);
        return p == null ? id : p.label();
    }

    /** 可选数据源清单：id / label / configured / active */
    public List<Map<String, Object>> options() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, WeatherProvider> e : providers.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getKey());
            item.put("label", e.getValue().label());
            item.put("configured", e.getValue().isConfigured());
            item.put("active", e.getKey().equals(source));
            out.add(item);
        }
        // 展示顺序：比赛官方 → 真实气象 → 离线模拟
        out.sort((a, b) -> Integer.compare(rank((String) a.get("id")), rank((String) b.get("id"))));
        return out;
    }

    private int rank(String id) {
        return switch (id) {
            case CONTEST -> 0;
            case OPEN_METEO -> 1;
            case SIMULATED -> 2;
            default -> 9;
        };
    }
}
