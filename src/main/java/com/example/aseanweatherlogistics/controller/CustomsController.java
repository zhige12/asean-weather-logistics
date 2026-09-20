package com.example.aseanweatherlogistics.controller;

import com.example.aseanweatherlogistics.service.CustomsEfficiencyService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通关时效接口（计划书 3.3）。
 * 支持查看口岸时效、模拟调整（拥堵/管控）与重置。
 */
@RestController
@RequestMapping("/api/customs")
public class CustomsController {

    private final CustomsEfficiencyService service;

    public CustomsController(CustomsEfficiencyService service) {
        this.service = service;
    }

    /** 全部口岸通关时效（含默认值与当前生效值）。 */
    @GetMapping("/efficiency")
    public List<Map<String, Object>> efficiency() {
        return service.listAll();
    }

    /** 模拟调整口岸通关时长，例如 POST {"hours": 6.0, "note": "口岸拥堵"}。 */
    @PostMapping("/efficiency/{portId}")
    public ResponseEntity<List<Map<String, Object>>> update(@PathVariable String portId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        double hours = body == null || body.get("hours") == null
                ? 5.0 : Double.parseDouble(String.valueOf(body.get("hours")));
        String note = body == null ? null : (String) body.get("note");
        service.update(portId, hours, note);
        return ResponseEntity.ok(service.listAll());
    }

    /** 重置指定口岸为默认时效。 */
    @DeleteMapping("/efficiency/{portId}")
    public List<Map<String, Object>> reset(@PathVariable String portId) {
        service.reset(portId);
        return service.listAll();
    }

    /** 重置全部口岸。 */
    @DeleteMapping("/efficiency")
    public List<Map<String, Object>> resetAll() {
        service.resetAll();
        return service.listAll();
    }
}
