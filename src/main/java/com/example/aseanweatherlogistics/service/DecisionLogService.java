package com.example.aseanweatherlogistics.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * 决策日志（演示第四幕"记录决策日志"）：熔断/恢复、沙盘调整、AI 分析、
 * 任务变更下发、各角色确认、叫应升级，全程留痕可回溯。
 */
@Service
public class DecisionLogService {

    public record Entry(long ts, String time, String category, String event, String detail) {
    }

    private static final int MAX_ENTRIES = 500;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    public void log(String category, String event, String detail) {
        entries.add(new Entry(System.currentTimeMillis(), LocalTime.now().format(FMT),
                category, event, detail == null ? "" : detail));
        // 防内存膨胀：超出上限丢弃最旧的一半
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Entry> recent(int n) {
        List<Entry> all = entries();
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }

    public void clear() {
        entries.clear();
    }
}
