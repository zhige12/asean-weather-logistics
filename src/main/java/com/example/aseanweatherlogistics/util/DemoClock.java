package com.example.aseanweatherlogistics.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 演示时刻计算：把通知文案里**写死的钟点**改成跟着真实时钟走。
 * <p>
 * 原先通知与联运卡片里写死「15:30 开始装船」「16:00 前完成装船」「17:00 后建议锚泊」，
 * 与演示实际时间脱节——评委一看表就对不上，而且各个角色的通知还会自相矛盾。
 * 这里统一按当前时间推导，并向上取到最近的整点/半点，读起来像真实的排班时刻。
 * <p>
 * 「装船开始」与「司机抵达截止」成对计算（{@link #boarding()}），保证抵达时间永远早于开船时间。
 */
public final class DemoClock {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 司机需提前多久抵达港区交接车辆（分钟） */
    private static final int ARRIVE_LEAD_MINUTES = 30;
    /**
     * 默认提前多久排装船（分钟）。
     * 取 3 小时：南宁→六景港区这一程是 110km / 约 1.5h，
     * 提前量太小会算出"物理上赶不到"的抵达截止时间。
     */
    private static final int BOARDING_LEAD_MINUTES = 180;

    private DemoClock() {
    }

    /**
     * 从现在起 plusMinutes 分钟，向上取到最近的 :00 / :30。
     * <p>
     * 关键：先把"现在"**向下取到半点**再加提前量。否则同一份演示里，
     * 派单时定型的通知（OutreachService 缓存文案）与每次请求现算的联运卡片
     * 可能跨过整/半点而报出相差 30 分钟的时刻。向下取整后，
     * 同一半小时内任意次调用结果完全一致。
     */
    private static LocalDateTime nextSlot(int plusMinutes) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = now.withMinute(now.getMinute() < 30 ? 0 : 30).withSecond(0).withNano(0);
        LocalDateTime t = base.plusMinutes(plusMinutes);
        int m = t.getMinute();
        int add = (m == 0) ? 0 : (m <= 30 ? 30 - m : 60 - m);
        return t.plusMinutes(add);
    }

    /** 装船相关的成对时刻，一次算出以保证两者自洽 */
    public record Boarding(String start, String arriveBy) {
    }

    /** 装船开始 / 司机抵达截止（HH:mm）。整套通知共用一次调用结果，避免各自取时刻而错位。 */
    public static Boarding boarding() {
        LocalDateTime slot = nextSlot(BOARDING_LEAD_MINUTES);
        return new Boarding(slot.format(HHMM), slot.minusMinutes(ARRIVE_LEAD_MINUTES).format(HHMM));
    }

    /** 一般性时刻建议：从现在起 plusMinutes 分钟取整/半点（HH:mm） */
    public static String slot(int plusMinutes) {
        return nextSlot(plusMinutes).format(HHMM);
    }
}