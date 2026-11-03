package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.AseanCity;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.example.aseanweatherlogistics.repository.AseanCityRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 东盟态势通道的四条硬约束固化（全部用桩数据，不碰网络）：
 * <ol>
 *   <li>排序与聚合：高风险置顶、区域峰值与分档计数必须和表格同源；</li>
 *   <li>额外要素缺项必须是 null：日最高/最低温、云量取不到时不许落成 0；</li>
 *   <li>节流：第二次访问不得再打上游（区域 bbox 是百 KB 级请求，赛事明令禁止暴力访问）；</li>
 *   <li>熔断 + 降级：连续失败后静默，且「没数据」必须报成没数据，不能渲染成一张全 LOW 的空表；</li>
 *   <li>前瞻预报与拉丁名：取不到的城市不能拿 0 mm 冒充「未来无雨」；界面切语言用的
 *       countryEn/roleEn 必须从实体一路带到响应。</li>
 * </ol>
 */
class AseanSituationServiceTest {

    /** 只喂 AMRI 需要的四个量（雨/风/能见度/气温），其余固定——本测试关心口径与降级，不关心格点解析。 */
    private static RealWeatherService.WeatherPoint point(String nodeId, double rain, double wind, double vis, double temp) {
        return new RealWeatherService.WeatherPoint(nodeId, 0, 0, temp, 60, rain, wind, vis, 1013,
                System.currentTimeMillis());
    }

    private static AseanCity city(String id, String name, String country, String cc, double lat, double lon) {
        AseanCity c = new AseanCity();
        c.setCityId(id);
        c.setName(name);
        c.setNameEn(id);
        c.setCountry(country);
        c.setCountryEn(cc);
        c.setCountryCode(cc);
        c.setRole("测试点位");
        c.setRoleEn("Test site");
        c.setLatitude(lat);
        c.setLongitude(lon);
        c.setCorridor("NNG".equals(id));
        return c;
    }

    private static class StubRepo extends AseanCityRepository {
        private final List<AseanCity> cities;

        StubRepo(List<AseanCity> cities) {
            this.cities = cities;
        }

        @Override
        public List<AseanCity> loadAll() {
            return cities;
        }
    }

    private static class StubProvider implements WeatherProvider {
        List<RealWeatherService.WeatherPoint> points = List.of();
        Map<String, Map<String, Double>> ext = Map.of();
        Map<String, Map<String, Double>> outlook = Map.of();
        RuntimeException failure;
        int calls;

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String label() {
            return "桩源";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<RealWeatherService.WeatherPoint> fetchForNodes(List<RoadNode> nodes) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return points;
        }

        @Override
        public Map<String, Map<String, Double>> fetchExtendedElements(List<RoadNode> nodes) {
            return ext;
        }

        @Override
        public Map<String, Map<String, Double>> fetchForecastOutlook(List<RoadNode> nodes) {
            return outlook;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AseanSituationService newService(StubRepo repo, StubProvider provider) {
        AseanSituationService s = new AseanSituationService(repo, new WeatherSourceService(List.of(provider), "stub"));
        setField(s, "minIntervalSeconds", 1800L);
        setField(s, "circuitFailureThreshold", 3L);
        setField(s, "circuitOpenSeconds", 1800L);
        setField(s, "ttlMinutes", 120L);
        setField(s, "warmOnStart", false);
        return s;
    }

    private static List<Map<String, Object>> rowsOf(Map<String, Object> out) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("cities");
        return rows;
    }

    @Test
    void situation_sortsHighRiskFirst_andAggregatesFromSameSource() {
        StubProvider provider = new StubProvider();
        provider.points = List.of(
                point("ASEAN_BKK", 60, 10, 8000, 32),   // 暴雨：指数最高
                point("ASEAN_NNG", 0, 8, 20000, 30),    // 晴
                point("ASEAN_SIN", 6, 12, 9000, 31));   // 中雨
        AseanSituationService svc = newService(new StubRepo(List.of(
                city("NNG", "南宁", "中国", "CN", 22.82, 108.32),
                city("BKK", "曼谷", "泰国", "TH", 13.75, 100.50),
                city("SIN", "新加坡", "新加坡", "SG", 1.35, 103.82))), provider);

        Map<String, Object> out = svc.situation(false);
        List<Map<String, Object>> rows = rowsOf(out);
        assertEquals(3, out.get("count"));
        assertEquals(3L, out.get("countries"));
        assertEquals("ASEAN_BKK".substring(6), rows.get(0).get("cityId"), "暴雨城市必须置顶");
        double first = ((Number) rows.get(0).get("index")).doubleValue();
        double last = ((Number) rows.get(rows.size() - 1).get("index")).doubleValue();
        assertTrue(first > last, "降序排列");

        // 区域峰值与分档计数必须与表格同源：把表格里的档位数一边数一遍
        Map<?, ?> peak = (Map<?, ?>) out.get("regionPeak");
        assertEquals(rows.get(0).get("cityId"), peak.get("cityId"));
        assertEquals(rows.get(0).get("index"), peak.get("index"));
        Map<?, ?> bands = (Map<?, ?>) out.get("severityBands");
        long bandTotal = 0;
        for (String k : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            bandTotal += ((Number) bands.get(k)).longValue();
        }
        assertEquals(3, bandTotal, "分档计数必须覆盖全部城市，不能漏档");
        assertNotNull(peak.get("mainHazard"));
        // 覆盖边界必须随响应下发：界面不能把「城市单点风险」说成「路线可用」
        assertTrue(String.valueOf(out.get("routingNote")).contains("不出路线"));
        assertTrue(String.valueOf(out.get("coverageNote")).contains("走廊"));
    }

    @Test
    void extendedElements_keepMissingAsNull_neverDefaultZero() {
        StubProvider provider = new StubProvider();
        provider.points = List.of(point("ASEAN_NNG", 0, 8, 20000, 30), point("ASEAN_BKK", 0, 8, 20000, 30));
        // 只给南宁拿到日较差与总云量，且没有 LCC；曼谷一个额外要素都没有
        provider.ext = Map.of("ASEAN_NNG", Map.of("TMAX", 36.6, "TMIN", 25.4, "TCC", 40.0));
        AseanSituationService svc = newService(new StubRepo(List.of(
                city("NNG", "南宁", "中国", "CN", 22.82, 108.32),
                city("BKK", "曼谷", "泰国", "TH", 13.75, 100.50))), provider);

        Map<String, Object> out = svc.situation(false);
        Map<String, Object> nng = rowsOf(out).stream()
                .filter(r -> "NNG".equals(r.get("cityId"))).findFirst().orElseThrow();
        Map<String, Object> bkk = rowsOf(out).stream()
                .filter(r -> "BKK".equals(r.get("cityId"))).findFirst().orElseThrow();
        assertEquals(36.6, ((Number) nng.get("tempMaxC")).doubleValue(), 0.001);
        assertNull(nng.get("lowCloudPct"), "源里没有 LCC 这项就必须是 null，不能拿总云量冒充");
        assertNull(bkk.get("tempMaxC"), "取不到的城市不能显示 0℃——那是编出来的日温");
        assertEquals(1L, out.get("extendedElementCities"));
        assertTrue(String.valueOf(out.get("extendedElementNote")).contains("11 个要素"));
    }

    @Test
    void forecastOutlook_andLatinNames_areCarriedThrough_orStayNull() {
        StubProvider provider = new StubProvider();
        provider.points = List.of(point("ASEAN_NNG", 0, 8, 20000, 30), point("ASEAN_BKK", 0, 8, 20000, 30));
        // 只给南宁拿到前瞻降雨，曼谷一个时效都没有
        provider.outlook = Map.of("ASEAN_NNG", Map.of("rain3hMm", 1.5, "rain6hMm", 4.0));
        AseanSituationService svc = newService(new StubRepo(List.of(
                city("NNG", "南宁", "中国", "CN", 22.82, 108.32),
                city("BKK", "曼谷", "泰国", "TH", 13.75, 100.50))), provider);

        Map<String, Object> out = svc.situation(false);
        Map<String, Object> nng = rowsOf(out).stream()
                .filter(r -> "NNG".equals(r.get("cityId"))).findFirst().orElseThrow();
        Map<String, Object> bkk = rowsOf(out).stream()
                .filter(r -> "BKK".equals(r.get("cityId"))).findFirst().orElseThrow();
        assertEquals(1.5, ((Number) nng.get("rainNext3hMm")).doubleValue(), 0.001);
        assertEquals(4.0, ((Number) nng.get("rainNext6hMm")).doubleValue(), 0.001);
        assertNull(bkk.get("rainNext6hMm"), "没有前瞻读数就不能显示 0 mm——那是「未来无雨」的假承诺");
        assertEquals(1L, out.get("forecastCities"));
        assertTrue(String.valueOf(out.get("forecastNote")).contains("前瞻"));
        // 拉丁名一路带到响应：非中文界面的地名/国名/定位靠这几个字段，不能靠术语表逐词硬凑
        assertEquals("CN", nng.get("countryEn"));
        assertEquals("Test site", nng.get("roleEn"));
    }

    @Test
    void secondCallInsideWindow_doesNotHitUpstreamAgain() {
        StubProvider provider = new StubProvider();
        provider.points = List.of(point("ASEAN_NNG", 0, 8, 20000, 30));
        AseanSituationService svc = newService(
                new StubRepo(List.of(city("NNG", "南宁", "中国", "CN", 22.82, 108.32))), provider);

        svc.situation(false);
        Map<String, Object> second = svc.situation(false);
        assertEquals(1, provider.calls, "半小时窗口内不得再打上游");
        assertTrue(Boolean.TRUE.equals(second.get("servedFresh")));
        Map<?, ?> stats = (Map<?, ?>) second.get("stats");
        assertEquals(1L, stats.get("upstreamRequests"));
        assertEquals(1L, stats.get("cacheHits"));
    }

    @Test
    void consecutiveFailures_openCircuit_andNeverFakeAnEmptyTable() {
        StubProvider provider = new StubProvider();
        provider.failure = new IllegalStateException("上游 500");
        AseanSituationService svc = newService(
                new StubRepo(List.of(city("NNG", "南宁", "中国", "CN", 22.82, 108.32))), provider);

        Map<String, Object> first = null;
        for (int i = 0; i < 3; i++) {
            first = svc.situation(false);
        }
        assertEquals(3, provider.calls);
        // 关键：失败时不给空表当「区域无风险」——count=0 + error 必须在，界面据此显错误态
        assertEquals(0, first.get("count"));
        assertNotNull(first.get("error"));
        assertTrue(Boolean.FALSE.equals(first.get("servedFresh")), "没有成功回源过不能声称供数新鲜");

        // 熔断已开：即使数据源恢复、即使带 refresh，也不再打上游（不重试轰炸）
        provider.failure = null;
        provider.points = List.of(point("ASEAN_NNG", 0, 8, 20000, 30));
        Map<String, Object> blocked = svc.situation(true);
        assertEquals(3, provider.calls, "熔断静默期内连手动刷新都不该回源");
        assertTrue(String.valueOf(blocked.get("error")).contains("熔断"));
        Map<?, ?> stats = (Map<?, ?>) blocked.get("stats");
        assertEquals(1L, stats.get("circuitOpens"));
    }
}
