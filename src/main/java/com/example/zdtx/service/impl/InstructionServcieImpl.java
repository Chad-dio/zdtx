package com.example.zdtx.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.dto.instruction.InstructionQueryDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.domain.vo.InstructionExVO;
import com.example.zdtx.service.InstructionServcie;
import com.example.zdtx.utils.HttpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.example.zdtx.constants.RedisConstants.*;
/**
 * InstructionServcieImpl
 * - 产出本轮调度顺序：优先级优先 -> 尽量少冲突 -> 兼顾等待时间 & 历史时长/波动
 * - 不做资源加锁，只返回排序后的列表
 */
@Service
@RequiredArgsConstructor
public class InstructionServcieImpl implements InstructionServcie {

    private final StringRedisTemplate stringRedisTemplate;

    // ===================== 调度权重 & 历史项（可调） =====================

    /** 基础权重 */
    private static final int Wp = 1000;  // 优先级权重（主导）
    private static final int Ww = 5;     // 等待时间(分钟)权重（防饿）
    private static final int Wc = 80;    // 冲突惩罚总权重（越大越偏好少冲突序列）

    /** 路径重叠惩罚 */
    private static final int PcOpp = 5;  // 与队尾比较时，反向重叠的单段惩罚
    private static final int PcSame = 1; // 与队尾比较时，同向重叠的轻微惩罚（可设为0或-1作为奖励）
    private static final int LOOKBACK = 3; // 与队尾最近几条比较

    /** 历史期望耗时 + 风险溢价（来自 stats:od:FROM|TO） */
    private static final double Wt = 0.01;      // 历史耗时惩罚权重（把毫秒量纲缩放到分值）
    private static final double K  = 1.0;       // 风险厌恶：对 std 的加权（mean + K*std）

    /** 冷启动兜底（没统计或样本很少时） */
    private static final long DEFAULT_OD_MEAN_MS = 12000L; // 无历史时的默认期望耗时（按现场节拍调）
    private static final long DEFAULT_OD_STD_MS  = 2000L;  // 无历史时的默认波动
    private static final long WARMUP_N = 5;                  // 样本少于 N 次时退火减弱历史项影响

    // ===================== 对外基础接口（与你原来一致） =====================

    @Override
    public Result<Boolean> addInstruction(InstructionAddDTO requestparm) {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "]addInstruction 方法被调用");
        if (requestparm == null) {
            return Result.error("指令为空");
        }
        System.out.println("[" + getCurrentTimestamp() + "]开始将指令\n" + requestparm +  "\n添加到等待队列");

        // 1) 进等待队列（score 用优先级；批量接口已用 p*1e13 - now）
        stringRedisTemplate.opsForZSet().add(
                TASK_WAITING_ZSET,
                requestparm.getInstructionCode(),
                requestparm.getPriority()
        );

        // 2) DTO -> Map，并把 value 全部转成 String
        Map<String, Object> raw = BeanUtil.beanToMap(
                requestparm,
                new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldNameEditor(name -> name)
        );

        Map<String, String> strMap = new HashMap<>(raw.size());
        raw.forEach((k, v) -> {
            if (v != null) strMap.put(k, String.valueOf(v));
        });
        // 建议写入 enqueueAt 供等待时长计算
        strMap.putIfAbsent("enqueueAt", String.valueOf(System.currentTimeMillis()));

        // 3) 写入 Hash（key、field、value 都是 String）
        String infoKey = TASK_INFO + requestparm.getInstructionCode();
        stringRedisTemplate.opsForHash().putAll(infoKey, strMap);
        System.out.println("[" + getCurrentTimestamp() + "]指令" + requestparm.getInstructionCode() + "添加成功");
        return Result.success(Boolean.TRUE, "添加成功");
    }

    @Override
    public Result<Void> addInstructions(List<InstructionAddDTO> requestparm) {
        if (requestparm == null || requestparm.isEmpty()) {
            return Result.success();
        }
        // 批量入队
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer keySer = new StringRedisSerializer();
            StringRedisSerializer valSer = new StringRedisSerializer();

            for (InstructionAddDTO dto : requestparm) {
                String code = dto.getInstructionCode();
                long now = System.currentTimeMillis();
                double score = dto.getPriority() * 1e13 - now; // 先优先级，再先来后到

                // ZADD task:waiting
                connection.zAdd(
                        Objects.requireNonNull(keySer.serialize(TASK_WAITING_ZSET)),
                        score,
                        Objects.requireNonNull(keySer.serialize(code))
                );

                String infoKey = TASK_INFO + code;
                Map<String, Object> map = BeanUtil.beanToMap(
                        dto,
                        new HashMap<>(),
                        CopyOptions.create().ignoreNullValue()
                );
                map.putIfAbsent("enqueueAt", now);

                Map<byte[], byte[]> hash = new HashMap<>();
                map.forEach((k, v) -> hash.put(
                        keySer.serialize(k),
                        valSer.serialize(String.valueOf(v))
                ));
                connection.hMSet(
                        Objects.requireNonNull(keySer.serialize(infoKey)),
                        hash
                );
            }
            return null;
        });
        return Result.success();
    }

    @Override
    public Result<Void> cancelInstruction(InstructionCancelDTO requestparm) {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "]cancelInstruction 方法被调用");
        String instructionCode = requestparm.getInstructionCode();
        Double score = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, instructionCode);
        if(score == null){
            System.out.println("[" + getCurrentTimestamp() + "]指令 " + instructionCode + " 已经启动，无法取消");
            return Result.error("指令已经启动");
        }
        stringRedisTemplate.opsForZSet().remove(TASK_WAITING_ZSET, instructionCode);
        String infoKey = TASK_INFO + instructionCode;
        stringRedisTemplate.delete(infoKey);
        System.out.println("[" + getCurrentTimestamp() + "]指令 " + instructionCode + " 取消成功");
        return Result.success("指令" + instructionCode + "取消成功");
    }

    @Override
    public Boolean queryInstruction(InstructionQueryDTO requestparm) {
        //向上游系统查询这个指令是否可以被释放
        String url = "xxxx";
        String s = HttpUtils.postForm(url, BeanUtil.beanToMap(
                requestparm,
                new HashMap<>(),
                CopyOptions.create().ignoreNullValue()
        ));
        JSONObject json = JSONUtil.parseObj(s);
        int code = json.getInt("responseCode");
        if (code != 0) {
            throw new RuntimeException("查询指令状态失败: " + json.getStr("responseMessage"));
        }
        return json.getBool("status", true);
    }

    @Override
    public Result<List<InstructionExVO>> getInstructions() throws InterruptedException {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "]getInstructions 方法被调用");
        System.out.println("[" + getCurrentTimestamp() + "]进入指令调度");
        Thread.sleep(1000);
        List<InstructionExVO> instructions = getInstructionsBySchedule(MAX_TASK);
        if (instructions.isEmpty()) {
            System.out.println("[" + getCurrentTimestamp() + "]暂无待执行的指令");
            return Result.success(Collections.emptyList(), "暂无待执行的指令");
        }
        System.out.println("[" + getCurrentTimestamp() + "]获取到待执行的指令：" + instructions.size() + " 条");
        System.out.println("具体为:");
        System.out.println(instructions);
        return Result.success(instructions, "获取成功");
    }

    public List<InstructionExVO> getInstructionsBySchedule(int size) {
        if (size <= 0) return Collections.emptyList();

        // 1) 候选取自等待队列（分数高在前）。如需窗口可把 -1 换为 windowSize-1
        Set<String> candidates = stringRedisTemplate.opsForZSet()
                .reverseRange(TASK_WAITING_ZSET, 0, -1);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 用 List 保持顺序，pipeline 的返回与此一一对应
        final List<String> codes = new ArrayList<>(candidates);

        // 2) pipeline 批量 HGETALL，结果就是 Map<Object,Object>
        final List<Object> results = stringRedisTemplate.executePipelined(
                new org.springframework.data.redis.core.SessionCallback<Object>() {
                    @Override
                    public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                            throws org.springframework.dao.DataAccessException {
                        for (String code : codes) {
                            operations.opsForHash().entries(TASK_INFO + code);
                        }
                        return null; // 返回 null 才会把 pipeline 结果带出来
                    }
                }
        );

        final int n = codes.size();
        final List<InstructionExVO> list = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            String code = codes.get(i);
            Map<Object, Object> m = null;
            if (results != null && i < results.size() && results.get(i) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> tmp = (Map<Object, Object>) results.get(i);
                m = tmp;
            }

            InstructionExVO vo = new InstructionExVO();
            vo.setInstructionCode(code);

            if (m != null && !m.isEmpty()) {
                vo.setLocationFrom((String) m.get("locationFrom"));
                vo.setLocationTo((String) m.get("locationTo"));
                Object p = m.get("priority");
                if (p != null) {
                    try { vo.setPriority(Integer.valueOf(p.toString())); } catch (NumberFormatException ignore) {}
                }
            }
            list.add(vo);
        }

        // 3) 调度算法：优先级 + 等待时间 + 历史项 + 最小冲突（纯内存，不加锁）
        schedule(list);

        // 4) 仅截取前 size 条返回（不修改、不删除 Redis）
        return list.size() > size ? list.subList(0, size) : list;
    }

    @Override
    public Result<Void> clear() {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "]clear 方法被调用");
        String pattern = "*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        System.out.println("[" + getCurrentTimestamp() + "]Redis 清理完成");
        return Result.success();
    }

    private String getCurrentTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return now.format(formatter);
    }

// ===================== 调度核心：评分 & 序列化 =====================

    /** 纯内存“最小冲突序列化”：优先级/等待 -> 历史项 -> 冲突，贪心生成顺序 */
    private void schedule(List<InstructionExVO> candidates) {
        final long now = System.currentTimeMillis();

        // 1) 准备扩展（等待、路径、历史项）
        Map<String, TaskCtx> ctx = new HashMap<>(candidates.size());
        for (InstructionExVO vo : candidates) {
            String code = vo.getInstructionCode();
            Map<Object, Object> m = stringRedisTemplate.opsForHash().entries(TASK_INFO + code);

            long enqueueAt = 0L;
            if (m != null && m.get("enqueueAt") != null) {
                try { enqueueAt = Long.parseLong(String.valueOf(m.get("enqueueAt"))); } catch (Exception ignore) {}
            } else {
                // 兜底：如果没有 enqueueAt，可用 ZSCORE 近似反推（p*1e13 - now）
                Double z = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, code);
                Integer p = vo.getPriority() == null ? 0 : vo.getPriority();
                if (z != null) enqueueAt = (long)(p * 1e13 - z);
            }
            long waitMin = Math.max(0, (now - enqueueAt) / 60_000);

            // 路径段（基于环路拓扑；多锚点会自动选最短）
            List<Seg> path = computePathSegments(vo.getLocationFrom(), vo.getLocationTo());

            // 历史期望耗时 + 风险（带冷启动退火；多锚点自动选最低历史代价）
            double histMs = historicalCostMs(vo.getLocationFrom(), vo.getLocationTo());

            // 基准分：优先级主导 + 等待抬升 - 历史耗时/风险惩罚
            int p = vo.getPriority() == null ? 0 : vo.getPriority();
            double base = Wp * (double)p + Ww * (double)waitMin - Wt * histMs;

            ctx.put(code, new TaskCtx(vo, base, path, waitMin));
        }

        // 2) 贪心构造顺序：每步挑“base - Wc*conflictPenalty”最高者
        List<InstructionExVO> ordered = new ArrayList<>(candidates.size());
        Set<String> remaining = new LinkedHashSet<>();
        for (InstructionExVO vo : candidates) remaining.add(vo.getInstructionCode());

        while (!remaining.isEmpty()) {
            String best = null;
            double bestScore = -1e100;

            for (String code : remaining) {
                TaskCtx t = ctx.get(code);
                double penalty = conflictPenalty(t, ordered, ctx);
                double finalScore = t.baseScore - Wc * penalty;
                if (finalScore > bestScore) {
                    bestScore = finalScore;
                    best = code;
                }
            }
            ordered.add(ctx.get(best).vo);
            remaining.remove(best);
        }

        candidates.clear();
        candidates.addAll(ordered);
    }

    /** 与队尾最近 LOOKBACK 条比较路径重叠：同向轻惩罚、反向重惩罚 */
    private double conflictPenalty(TaskCtx cand, List<InstructionExVO> tail, Map<String, TaskCtx> ctx) {
        if (tail.isEmpty()) return 0.0;
        int from = Math.max(0, tail.size() - LOOKBACK);
        double penalty = 0;

        for (int i = from; i < tail.size(); i++) {
            TaskCtx t = ctx.get(tail.get(i).getInstructionCode());
            for (Seg s1 : cand.path) {
                for (Seg s2 : t.path) {
                    if (!s1.id.equals(s2.id)) continue;
                    if (s1.dir == s2.dir) penalty += PcSame;
                    else penalty += PcOpp;
                }
            }
        }
        return penalty;
    }

    /** 历史项：读取 stats:od:FROM|TO 的 mean/std/count；多锚点取“历史代价最低”的组合；无数据退火 */
    private double historicalCostMs(String from, String to) {
        List<String> fromAs = normalizeToGrayAnchors(from);
        List<String> toAs   = normalizeToGrayAnchors(to);
        if (fromAs.isEmpty() || toAs.isEmpty()) {
            // 没法定位到主环锚点：不施加历史惩罚
            return 0.0;
        }

        double best = Double.POSITIVE_INFINITY;

        for (String f : fromAs) {
            for (String t : toAs) {
                String key = "stats:od:" + f + "|" + t;

                Object meanStr = stringRedisTemplate.opsForHash().get(key, "mean_ms");
                Object stdStr  = stringRedisTemplate.opsForHash().get(key, "std_ms");
                Object cntStr  = stringRedisTemplate.opsForHash().get(key, "count");

                double mean = DEFAULT_OD_MEAN_MS;
                double std  = DEFAULT_OD_STD_MS;
                long   cnt  = 0;

                try { if (meanStr != null) mean = Double.parseDouble(String.valueOf(meanStr)); } catch (Exception ignore) {}
                try { if (stdStr  != null) std  = Double.parseDouble(String.valueOf(stdStr));  } catch (Exception ignore) {}
                try { if (cntStr  != null) cnt  = Long.parseLong(String.valueOf(cntStr));      } catch (Exception ignore) {}

                double base = mean + K * std;
                double scale = Math.min(1.0, cnt / (double) Math.max(1, WARMUP_N)); // 冷启动退火
                double cost = base * scale;

                if (cost < best) best = cost;
            }
        }
        return Double.isInfinite(best) ? 0.0 : best;
    }

    /** 内部上下文 */
    private static class TaskCtx {
        final InstructionExVO vo;
        final double baseScore;
        final List<Seg> path;
        final long waitMin;
        TaskCtx(InstructionExVO vo, double baseScore, List<Seg> path, long waitMin) {
            this.vo = vo; this.baseScore = baseScore; this.path = path; this.waitMin = waitMin;
        }
    }

// ===================== 环路建模（灰色主环 + 出入口映射，支持多锚点） =====================

    /** 段定义：id 为“无向段”(两端字母序拼接)，dir 表示方向（+1 顺时针，-1 逆时针） */
    public static class Seg {
        public final String id;
        public final int dir; // +1 顺时针，-1 逆时针
        public Seg(String id, int dir) { this.id = id; this.dir = dir; }
        @Override public String toString(){ return "Seg{id="+id+",dir="+dir+"}"; }
    }

    /** 方向策略（保留扩展性） */
    public enum DirectionPolicy {
        SHORTEST, CLOCKWISE, COUNTERCLOCKWISE
    }

    /**
     * 灰色主环顺时针顺序（传送带主环所有灰色节点，按顺时针依次列出）
     */
    private static final List<String> RING = List.of(
            "G01","G02","G03","G04","G05","G06","G07","G08","G09","G10",
            "G11","G12","G13","G14","G15","G16","G17","G18","G19","G20",
            "G21","G22","G23","G24","G25","G26","G27","G28","G29","G30",
            "G31","G32","G33","G34","G35","G36","G37","G38","G39","G40",
            "G41","G42","G43","G44","G45","G46","G47","G48","G49","G50",
            "G51","G52","G53","G54","G55","G56","G57","G58","G59","G60",
            "G61","G62","G63","G64","G65","G66","G67","G68"
    );

    /**
     * 出入口（彩色）节点 → 灰色主环节点的【一个或多个】锚点
     * - 若一个口在图上有两根蓝线/两条分支接到主环两个灰点，就写成两个锚点；
     * - 名称统一大写；示例里 IN2_* 展示了“多锚点”的写法。
     */
    private static final Map<String, List<String>> ATTACH = new HashMap<String, List<String>>() {{
        put("IN1_ENTRY",   Arrays.asList("G68"));  put("IN1_EXIT",   Arrays.asList("G68"));
        put("OUT1_ENTRY",  Arrays.asList("G01"));  put("OUT1_EXIT",  Arrays.asList("G01"));

        // 这里演示“多锚点”：IN2 在主环上有两个落点（请按实际替换 Gxx）
        put("IN2_ENTRY",   Arrays.asList("G65","G66"));
        put("IN2_EXIT",    Arrays.asList("G65","G66"));
        put("OUT2_ENTRY",  Arrays.asList("G04"));
        put("OUT2_EXIT",   Arrays.asList("G04"));

        put("IN3_ENTRY",   Arrays.asList("G63", "G62"));  put("IN3_EXIT", Arrays.asList("G63", "G62"));
        put("OUT3_ENTRY",  Arrays.asList("G63", "G62"));  put("OUT3_EXIT", Arrays.asList("G63", "G62"));

        put("IN4_ENTRY",   Arrays.asList("G59"));  put("IN4_EXIT",   Arrays.asList("G60"));
        put("OUT4_ENTRY",  Arrays.asList("G10"));  put("OUT4_EXIT",  Arrays.asList("G10"));

        put("IN5_ENTRY",   Arrays.asList("G57", "G56"));  put("IN5_EXIT", Arrays.asList("G57", "G56"));
        put("OUT5_ENTRY",  Arrays.asList("G13"));  put("OUT5_EXIT",  Arrays.asList("G12"));

        put("IN6_ENTRY",   Arrays.asList("G53"));  put("IN6_EXIT",   Arrays.asList("G54"));
        put("OUT6_ENTRY",  Arrays.asList("G15"));  put("OUT6_EXIT",  Arrays.asList("G15"));

        put("IN7_ENTRY",   Arrays.asList("G51"));  put("IN7_EXIT",   Arrays.asList("G51"));
        put("OUT7_ENTRY",  Arrays.asList("G17"));  put("OUT7_EXIT",  Arrays.asList("G17"));

        put("IN8_ENTRY",   Arrays.asList("G49"));  put("IN8_EXIT",   Arrays.asList("G49"));
        put("OUT8_ENTRY",  Arrays.asList("G20"));  put("OUT8_EXIT",  Arrays.asList("G20"));

        put("IN9_ENTRY",   Arrays.asList("G46"));  put("IN9_EXIT",   Arrays.asList("G47"));
        put("OUT9_ENTRY",  Arrays.asList("G22"));  put("OUT9_EXIT",  Arrays.asList("G22"));

        put("IN10_ENTRY",  Arrays.asList("G44", "G43"));  put("IN10_EXIT",  Arrays.asList("G44", "G43"));
        put("OUT10_ENTRY", Arrays.asList("G24", "G25"));  put("OUT10_EXIT", Arrays.asList("G24", "G25"));

        put("IN11_ENTRY",  Arrays.asList("G41", "G40"));  put("IN11_EXIT",  Arrays.asList("G41", "G40"));
        put("OUT11_ENTRY", Arrays.asList("G26", "G27"));  put("OUT11_EXIT", Arrays.asList("G26", "G27"));

        put("IN12_ENTRY",  Arrays.asList("G38"));  put("IN12_EXIT",  Arrays.asList("G38"));
    }};

    /** 灰色主环节点索引缓存（统一大写） */
    private static final Map<String,Integer> POS = new HashMap<>();
    static {
        for (int i = 0; i < RING.size(); i++) POS.put(RING.get(i).toUpperCase(), i);
    }

    /** 把任意业务名归一化为“一个或多个灰色锚点”；若本身是灰点名则返回单元素列表 */
    private static List<String> normalizeToGrayAnchors(String raw) {
        if (raw == null) return Collections.emptyList();
        String s = raw.trim().toUpperCase();

        // 若本身就是灰点名（G01..G68），直接返回
        if (POS.containsKey(s)) return Collections.singletonList(s);

        // 若是出入口名，返回配置的一个或多个锚点
        List<String> anchors = ATTACH.get(s);
        if (anchors != null && !anchors.isEmpty()) {
            List<String> list = new ArrayList<>(anchors.size());
            for (String a : anchors) list.add(a.toUpperCase());
            return list;
        }

        // 未知：返回空
        return Collections.emptyList();
    }

    /** 对外：计算 from→to 的段序列（在多个锚点组合中选“最短”，相等时顺时针） */
    private static List<Seg> computePathSegments(String from, String to) {
        return computePathSegments(from, to, DirectionPolicy.SHORTEST);
    }

    /** 在起点候选×终点候选的所有组合里，挑最优路径（最短/顺时针/逆时针策略可选） */
    private static List<Seg> computePathSegments(String from, String to, DirectionPolicy policy) {
        List<String> fromAs = normalizeToGrayAnchors(from);
        List<String> toAs   = normalizeToGrayAnchors(to);
        if (fromAs.isEmpty() || toAs.isEmpty()) return Collections.emptyList();

        List<Seg> best = null;
        int bestLen = Integer.MAX_VALUE;

        for (String F : fromAs) {
            for (String T : toAs) {
                List<Seg> path = pathBetweenGray(F, T, policy);
                if (path.size() < bestLen) {
                    best = path;
                    bestLen = path.size();
                }
            }
        }
        return best == null ? Collections.emptyList() : best;
    }

    /** 只在两个“灰点”之间展开路径（保持你的顺/逆/最短逻辑） */
    private static List<Seg> pathBetweenGray(String fromGray, String toGray, DirectionPolicy policy) {
        Integer si = POS.get(fromGray);
        Integer ti = POS.get(toGray);
        if (si == null || ti == null || si.equals(ti)) {
            return Collections.emptyList();
        }
        int n = RING.size();

        // 顺时针
        List<Seg> cw = new ArrayList<>();
        for (int i = si; i != ti; i = (i + 1) % n) {
            int j = (i + 1) % n;
            String a = RING.get(i), b = RING.get(j);
            String undirected = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            cw.add(new Seg(undirected, +1));
        }

        // 逆时针
        List<Seg> ccw = new ArrayList<>();
        for (int i = si; i != ti; i = (i - 1 + n) % n) {
            int j = (i - 1 + n) % n;
            String a = RING.get(i), b = RING.get(j);
            String undirected = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            ccw.add(new Seg(undirected, -1));
        }

        switch (policy) {
            case CLOCKWISE:        return cw;
            case COUNTERCLOCKWISE: return ccw;
            default: return (ccw.size() < cw.size()) ? ccw : cw; // 最短；相等取顺时针
        }
    }

    public static void main(String[] args) {
        // 用 null 构造（本地测试不连 Redis）
        InstructionServcieImpl svc = new InstructionServcieImpl(null);

        // 构造三条任务（你也可以改成真实的 IN/OUT 名）
        InstructionExVO t1 = new InstructionExVO();
        t1.setInstructionCode("T001");
        t1.setLocationFrom("IN1_EXIT");
        t1.setLocationTo("OUT1_ENTRY");
        t1.setPriority(3);

        InstructionExVO t2 = new InstructionExVO();
        t2.setInstructionCode("T002");
        t2.setLocationFrom("IN2_EXIT");     // 演示“多锚点”的入口
        t2.setLocationTo("OUT3_ENTRY");
        t2.setPriority(2);

        InstructionExVO t3 = new InstructionExVO();
        t3.setInstructionCode("T003");
        t3.setLocationFrom("G10");          // 灰点到灰点
        t3.setLocationTo("G20");
        t3.setPriority(1);

        List<InstructionExVO> list = new ArrayList<>();
        list.add(t1);
        list.add(t2);
        list.add(t3);

        // 1) 打印路径展开测试
        System.out.println("== 路径展开 ==");
        for (InstructionExVO vo : list) {
            List<Seg> path = computePathSegments(vo.getLocationFrom(), vo.getLocationTo());
            System.out.println(vo.getInstructionCode() + " [" + vo.getLocationFrom() + " -> " + vo.getLocationTo() + "]");
            System.out.println("  段数=" + path.size() + "  详情=" + pathToString(path));
        }

        // 2) 跑调度
        svc.schedule(list);

        // 3) 打印调度顺序
        System.out.println("\n== 调度顺序（高分在前） ==");
        for (int i = 0; i < list.size(); i++) {
            InstructionExVO vo = list.get(i);
            System.out.println((i + 1) + ". " + vo.getInstructionCode()
                    + " (p=" + (vo.getPriority() == null ? 0 : vo.getPriority())
                    + ", " + vo.getLocationFrom() + " -> " + vo.getLocationTo() + ")");
        }
    }

    /** 小工具：把段列表打印得更好看 */
    private static String pathToString(List<Seg> path) {
        if (path == null || path.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.size(); i++) {
            Seg s = path.get(i);
            sb.append(s.id).append(s.dir > 0 ? "↑" : "↓");
            if (i < path.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

}
