package com.example.zdtx.service.impl;

import com.example.zdtx.domain.dto.status.StatusUpdateDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.example.zdtx.constants.RedisConstants.*;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final StringRedisTemplate stringRedisTemplate;

    // ====================== TODO：可配置参数 ======================
    /** TODO[必填] EMA 平滑系数（0<alpha<=1；越大越“相信最近”），建议 0.05~0.3 */
    private static final double ALPHA = 0.1;

    /** TODO[可选] 统计 Hash 的字段名（不建议改） */
    private static final String F_EMA1 = "ema_x";    // EMA(x) —— 时长的指数滑动均值（毫秒）
    private static final String F_EMA2 = "ema_x2";   // EMA(x^2) —— 时长平方的指数滑动均值
    private static final String F_MEAN = "mean_ms";  // 导出：均值（毫秒）
    private static final String F_STD  = "std_ms";   // 导出：标准差（毫秒）
    private static final String F_CNT  = "count";    // 非必须：记录参与过多少次（仅供参考）

    /** TODO[可选] 统计 Key 前缀（按 OD 维度） */
    private static final String OD_KEY_PREFIX = "stats:od:"; // stats:od:FROM|TO

    @Override
    public Result<Boolean> updateStatus(StatusUpdateDTO requestparm) {
        System.out.println("[" + ts() + "]updateStatus 方法被调用");
        if (requestparm == null
                || isBlank(requestparm.getInstructionCode())
                || isBlank(requestparm.getLocationFrom())
                || isBlank(requestparm.getLocationTo())) {
            System.out.println("[" + ts() + "]参数非法：指令号/起点/终点不能为空");
            return Result.error("参数非法：指令号/起点/终点不能为空");
        }

        final String code = requestparm.getInstructionCode().trim();
        final long now = requestparm.getTime() != null
                ? requestparm.getTime().getTime()
                : System.currentTimeMillis();

        // 你现在用的 key 规则：TASK_COMPLETED_SET + instructionCode
        // 含义：第一次写入保存 startedAt(ms)，第二次写入覆盖成 duration(ms)
        final String key = TASK_COMPLETED_SET + code;

//        System.out.println("[" + ts() + "]尝试写入启动时间：key = " + key + "，时间 = " + now);
//
//        // === 1) 尝试写入“启动时间”（原子、幂等） ===
//        Boolean firstStart = stringRedisTemplate.opsForValue()
//                .setIfAbsent(key, String.valueOf(now));
//        Double score = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, code);
//        if (Boolean.TRUE.equals(firstStart) && score != null) {
//            // 启动：从等待队列移除该成员，并清理详情（如果有）
//            stringRedisTemplate.opsForZSet().remove(TASK_WAITING_ZSET, code);
//            stringRedisTemplate.delete(TASK_INFO + code);
//            System.out.println("[" + ts() + "]启动时间记录成功，移除等待队列中的指令，并清理详情");
//            return Result.success(Boolean.TRUE, "启动已记录");
//        }

        // === 2) 已经记录过启动 -> 视为“完成”，计算耗时 ===
        String startedStr = stringRedisTemplate.opsForValue().get(key);
        long startedAt;
        try { startedAt = Long.parseLong(startedStr); }
        catch (Exception e) { startedAt = now; }
        long deltaMs = Math.max(0L, now - startedAt);

        // 覆盖为耗时（毫秒）
        stringRedisTemplate.opsForValue().set(key, String.valueOf(deltaMs));
        System.out.println("[" + ts() + "]指令已完成：开始=" + startedAt + "，耗时=" + deltaMs + "ms");

        // === 3) 使用该耗时更新 OD 统计（EMA 均值 + 标准差） ===
        final String from = requestparm.getLocationFrom();
        final String to   = requestparm.getLocationTo();
        final String odKey = buildOdKey(from, to); // stats:od:FROM|TO

        // 读旧值
        String ema1Str = (String) stringRedisTemplate.opsForHash().get(odKey, F_EMA1);
        String ema2Str = (String) stringRedisTemplate.opsForHash().get(odKey, F_EMA2);
        String cntStr  = (String) stringRedisTemplate.opsForHash().get(odKey, F_CNT);

        double ema1 = parseOrDefault(ema1Str, deltaMs);       // 首次：用首个样本初始化
        double ema2 = parseOrDefault(ema2Str, deltaMs * 1.0 * deltaMs);
        long   cnt  = parseOrDefault(cntStr, 0L);

        // EMA 更新： s_new = (1-α)*s_old + α*x
        double newEma1 = (1 - ALPHA) * ema1 + ALPHA * deltaMs;
        double newEma2 = (1 - ALPHA) * ema2 + ALPHA * (deltaMs * 1.0 * deltaMs);

        // 方差与标准差（非负裁剪）
        double var = newEma2 - newEma1 * newEma1;
        if (var < 0) var = 0;
        double std = Math.sqrt(var);

        // 写回（Hash）
        stringRedisTemplate.opsForHash().put(odKey, F_EMA1, String.valueOf(newEma1));
        stringRedisTemplate.opsForHash().put(odKey, F_EMA2, String.valueOf(newEma2));
        stringRedisTemplate.opsForHash().put(odKey, F_MEAN, String.valueOf(newEma1));
        stringRedisTemplate.opsForHash().put(odKey, F_STD,  String.valueOf(std));
        stringRedisTemplate.opsForHash().put(odKey, F_CNT,  String.valueOf(cnt + 1));

        System.out.println("[" + ts() + "]OD统计更新 → key=" + odKey
                + " mean_ms=" + (long)newEma1 + " std_ms=" + (long)std
                + " (alpha=" + ALPHA + ", count=" + (cnt + 1) + ")");

        return Result.success(Boolean.TRUE, "完成已记录并更新统计");
    }

    // ====================== 工具方法 ======================
    private static String buildOdKey(String from, String to) {
        // 统一大写并去空格
        String f = normalize(from);
        String t = normalize(to);
        return OD_KEY_PREFIX + f + "|" + t;
    }

    private static String normalize(String s) {
        if (s == null) return "NULL";
        return s.trim().toUpperCase();
    }

    private static double parseOrDefault(String s, double def) {
        try { return s == null ? def : Double.parseDouble(s); }
        catch (Exception e) { return def; }
    }
    private static long parseOrDefault(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); }
        catch (Exception e) { return def; }
    }

    private static String ts() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
