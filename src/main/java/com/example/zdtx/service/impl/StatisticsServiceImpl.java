package com.example.zdtx.service.impl;

import com.example.zdtx.domain.dto.status.StatusUpdateDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.example.zdtx.constants.ParameterConstants.STATS_PROCESSING_TIME_LOWER;
import static com.example.zdtx.constants.ParameterConstants.STATS_PROCESSING_TIME_UPPER;
import static com.example.zdtx.constants.RedisConstants.*;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final StringRedisTemplate stringRedisTemplate;

    // 平滑系数（0<alpha<=1）
    private static final double ALPHA = 0.1;

    // Hash 字段名
    private static final String F_EMA1 = "ema_x";
    private static final String F_EMA2 = "ema_x2";
    private static final String F_MEAN = "mean_ms";
    private static final String F_STD  = "std_ms";
    private static final String F_CNT  = "count";

    private static final String OD_KEY_PREFIX = "stats:od:";

    @Override
    public Result<Boolean> updateStatus(StatusUpdateDTO requestparm) {
        System.out.println("[" + ts() + "] updateStatus 调用");

        if (requestparm == null
                || isBlank(requestparm.getInstructionCode())
                || isBlank(requestparm.getLocationFrom())
                || isBlank(requestparm.getLocationTo())) {
            return Result.error("参数非法：指令号/起点/终点不能为空");
        }

        String code = requestparm.getInstructionCode().trim();
        long now = requestparm.getTime() != null
                ? requestparm.getTime().getTime()
                : System.currentTimeMillis();

        // 单条任务耗时：读取起始时间 → now - startedAt
        String startedStr = stringRedisTemplate.opsForValue().get(TASK_COMPLETED_SET + code);
        long startedAt;
        try {
            startedAt = Long.parseLong(startedStr);
        } catch (Exception e) {
            startedAt = now;
        }
        long deltaMs = Math.max(0L, now - startedAt);

        // 回写本条任务耗时
        stringRedisTemplate.opsForValue().set(TASK_COMPLETED_SET + code, String.valueOf(deltaMs));

        // ---------------- OD 耗时统计 ----------------
        String from = requestparm.getLocationFrom();
        String to   = requestparm.getLocationTo();
        String odKey = buildOdKey(from, to);

        updateEmaStats(odKey, deltaMs,
                "OD统计 → " + odKey);

        // ---------------- 容器连续任务统计 ----------------
        String container = normalize(requestparm.getContainerCode());
        String containerLastKey = CONTAINER_LAST + container;
        String containerDurationKey = CONTAINER_DURATION + container;

        // 读取该容器上一次的结束时间 + 上一次任务的 to
        String lastFinishStr = (String) stringRedisTemplate.opsForHash()
                .get(containerLastKey, "last_finish_ts");
        String lastToStr = (String) stringRedisTemplate.opsForHash()
                .get(containerLastKey, "last_to");

        // 是否连续：上一次的 to 必须等于当前 from（均 normalize）
        String currFrom = normalize(from);
        boolean isContinuous = lastFinishStr != null
                && lastToStr != null
                && lastToStr.equals(currFrom);

        if (isContinuous) {
            long lastFinish = parseOrDefault(lastFinishStr, now);
            long containerDelta = Math.max(0L, now - lastFinish);

            //即便是连续，也需要检查是否位于合理区间
            if(containerDelta >= STATS_PROCESSING_TIME_LOWER && containerDelta <= STATS_PROCESSING_TIME_UPPER){
                updateEmaStats(
                        containerDurationKey,
                        containerDelta,
                        "容器连续任务统计 → " + container
                );
            }
        }

        // 无论是否连续，都更新容器 last 信息
        stringRedisTemplate.opsForHash().put(containerLastKey, "last_finish_ts", String.valueOf(now));
        stringRedisTemplate.opsForHash().put(containerLastKey, "last_to", normalize(to));

        return Result.success(Boolean.TRUE, "完成更新");
    }

    // ---------------- 通用 EMA 统计 ----------------
    private void updateEmaStats(String hashKey, long sampleMs, String logPrefix) {
        String ema1Str = (String) stringRedisTemplate.opsForHash().get(hashKey, F_EMA1);
        String ema2Str = (String) stringRedisTemplate.opsForHash().get(hashKey, F_EMA2);
        String cntStr  = (String) stringRedisTemplate.opsForHash().get(hashKey, F_CNT);

        double ema1 = parseOrDefault(ema1Str, (double) sampleMs);
        double ema2 = parseOrDefault(ema2Str, (double) sampleMs * sampleMs);
        long cnt    = parseOrDefault(cntStr, 0L);

        double newEma1 = (1 - ALPHA) * ema1 + ALPHA * sampleMs;
        double newEma2 = (1 - ALPHA) * ema2 + ALPHA * (sampleMs * 1.0 * sampleMs);

        double var = newEma2 - newEma1 * newEma1;
        if (var < 0) var = 0;
        double std = Math.sqrt(var);

        stringRedisTemplate.opsForHash().put(hashKey, F_EMA1, String.valueOf(newEma1));
        stringRedisTemplate.opsForHash().put(hashKey, F_EMA2, String.valueOf(newEma2));
        stringRedisTemplate.opsForHash().put(hashKey, F_MEAN, String.valueOf(newEma1));
        stringRedisTemplate.opsForHash().put(hashKey, F_STD,  String.valueOf(std));
        stringRedisTemplate.opsForHash().put(hashKey, F_CNT,  String.valueOf(cnt + 1));

        System.out.println("[" + ts() + "] " + logPrefix
                + " mean=" + (long)newEma1
                + " std=" + (long)std
                + " count=" + (cnt + 1));
    }

    // ---------------- 工具方法 ----------------
    private static String buildOdKey(String from, String to) {
        return OD_KEY_PREFIX + normalize(from) + "|" + normalize(to);
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
