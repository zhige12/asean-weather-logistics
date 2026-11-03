package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.AseanSituationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 东盟态势端点（门户「东盟态势」视图）。
 *
 * <ul>
 *   <li>{@code GET /api/asean/situation}：区域热点城市实况 + 单点 AMRI 指数（默认命中缓存）</li>
 *   <li>{@code GET /api/asean/situation?refresh=true}：手动刷新，可越过常规窗口但仍受熔断约束</li>
 * </ul>
 *
 * <p>响应除城市行外还带 {@code severityBands}（各档计数）、{@code regionPeak}（本区域最不利城市）、
 * {@code servedFresh}/{@code stale}/{@code stats}（新鲜度与回源审计计数）与 {@code coverageNote}
 * /{@code routingNote}（覆盖边界声明），界面据此如实标注，不把展示型数据说成算路依据。
 */
@RestController
@RequestMapping("/api/asean")
public class AseanSituationController {

    private final AseanSituationService situationService;

    public AseanSituationController(AseanSituationService situationService) {
        this.situationService = situationService;
    }

    /** 区域态势：城市 × 实况要素 × AMRI 单点指数，高风险置顶。 */
    @GetMapping("/situation")
    public Map<String, Object> situation(
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return situationService.situation(refresh);
    }
}
