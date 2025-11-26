package com.example.zdtx.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.dto.instruction.InstructionQueryDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.domain.vo.InstructionExVO;
import com.example.zdtx.service.InstructionServcie;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.example.zdtx.constants.RedisConstants.*;

/**
 * 指令调度实现：
 * 1. 按优先级 + 等待时间 + 历史耗时打分排序
 * 2. 在一轮调度内，用本地 nodePlan 模拟各终点的占用情况，决定本轮 ready / deferred
 * 3. 不做加锁，只给出排序结果
 */
@Service
@RequiredArgsConstructor
public class InstructionServcieImpl implements InstructionServcie {

    private final StringRedisTemplate stringRedisTemplate;

    // ===================== 调度打分相关 =====================

    // 优先级、等待时间权重
    private static final int Wp = 1000;  // priority 权重
    private static final int Ww = 5;     // 等待时间(分钟) 权重

    // 历史耗时（来自 stats:od:FROM|TO）
    private static final double Wt = 0.01;  // 把毫秒缩放成分数
    private static final double K  = 1.0;   // mean + K*std

    // OD 统计冷启动
    private static final long DEFAULT_OD_MEAN_MS = 12000L;
    private static final long DEFAULT_OD_STD_MS  = 2000L;
    private static final long WARMUP_N           = 5;

    // ===================== 节点预估可用时间 =====================

    // 每个终点的预计“可再接任务”的时间
    private static final String NODE_AVAILABLE_PREFIX = "node:available:";

    // 没历史数据时的默认行驶时间
    private static final long DEFAULT_TRAVEL_MS = DEFAULT_OD_MEAN_MS;

    // 到站后的处理时间（简单用常量，后面可以再细化）
    private static final long DEFAULT_PROCESS_MS = 10000L;

    // 允许稍微提前一点的裕量
    private static final long SAFE_EARLY_ARRIVE_MS = 5000L;

    // ===================== 对外接口 =====================

    @Override
    public Result<Boolean> addInstruction(InstructionAddDTO requestparm) {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "] addInstruction 调用");
        if (requestparm == null) {
            return Result.error("指令为空");
        }
        System.out.println("[" + getCurrentTimestamp() + "] 开始入队指令：" + requestparm);

        // 1) 写入等待队列（score 暂用优先级）
        stringRedisTemplate.opsForZSet().add(
                TASK_WAITING_ZSET,
                requestparm.getInstructionCode(),
                requestparm.getPriority()
        );

        // 2) DTO -> Map，全部转成 String 存 Hash
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
        // 入队时间，后续算等待用
        strMap.putIfAbsent("enqueueAt", String.valueOf(System.currentTimeMillis()));

        String infoKey = TASK_INFO + requestparm.getInstructionCode();
        stringRedisTemplate.opsForHash().putAll(infoKey, strMap);

        System.out.println("[" + getCurrentTimestamp() + "] 指令 " + requestparm.getInstructionCode() + " 入队完成");
        return Result.success(Boolean.TRUE, "添加成功");
    }

    @Override
    public Result<Void> addInstructions(List<InstructionAddDTO> requestparm) {
        if (requestparm == null || requestparm.isEmpty()) {
            return Result.success();
        }

        // 批量入队：zset + hash
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer keySer = new StringRedisSerializer();
            StringRedisSerializer valSer = new StringRedisSerializer();

            for (InstructionAddDTO dto : requestparm) {
                String code = dto.getInstructionCode();
                long now = System.currentTimeMillis();
                // score = priority + 轻量的先来后到
                double score = dto.getPriority() * 1e13 - now;

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
    public Result<String> cancelInstruction(InstructionCancelDTO requestparm) {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "] cancelInstruction 调用");
        String instructionCode = requestparm.getInstructionCode();
        Double score = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, instructionCode);

        // 不在等待队列里，视作已经启动
        if (score == null) {
            return Result.success("指令已经启动", "指令" + instructionCode + "取消成功");
        }

        stringRedisTemplate.opsForZSet().remove(TASK_WAITING_ZSET, instructionCode);
        String infoKey = TASK_INFO + instructionCode;
        stringRedisTemplate.delete(infoKey);
        System.out.println("[" + getCurrentTimestamp() + "] 指令 " + instructionCode + " 取消完成");
        return Result.success("指令未启动", "指令" + instructionCode + "取消成功");
    }

    @Override
    public Boolean queryInstruction(InstructionQueryDTO requestparm) {
        // 与上游对接时再实现，这里先默认都可下发
        return true;
    }

    @Override
    public Result<List<InstructionExVO>> getInstructions() throws InterruptedException {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "] getInstructions 调用");
        System.out.println("[" + getCurrentTimestamp() + "] 开始调度");

        // 1) 按打分拿一批候选指令
        List<InstructionExVO> scheduled = getInstructionsBySchedule(MAX_TASK);
        if (scheduled == null || scheduled.isEmpty()) {
            System.out.println("[" + getCurrentTimestamp() + "] 当前没有待执行指令");
            return Result.success(Collections.emptyList(), "暂无待执行的指令");
        }

        // 2) 按顺序逐个尝试：上游是否允许 + 按本轮节点占用计划判断是否能发
        List<InstructionExVO> ready = new ArrayList<>(scheduled.size());
        List<InstructionExVO> deferred = new ArrayList<>();
        final long now = System.currentTimeMillis();

        // 本轮调度用的“节点可用时间计划”，只在内存里维护
        Map<String, Long> nodePlan = new HashMap<>();

        for (InstructionExVO vo : scheduled) {
            Boolean upstreamOk = queryInstruction(
                    new InstructionQueryDTO(vo.getLocationFrom(), vo.getLocationTo())
            );

            boolean etaOk = canArriveWhenNodeFreeInPlan(vo, now, nodePlan);

            if (Boolean.TRUE.equals(upstreamOk) && etaOk) {
                ready.add(vo);
            } else {
                deferred.add(vo);
            }
        }

        // 3) ready 在前，deferred 在后
        List<InstructionExVO> ordered = new ArrayList<>(ready.size() + deferred.size());
        ordered.addAll(ready);
        ordered.addAll(deferred);

        // 4) 对 ready 的任务记录启动时间，并把终点的“预计可用时间”写回 Redis
        ready.forEach(instruction -> {
            String code = instruction.getInstructionCode();
            String key = TASK_COMPLETED_SET + code;
            System.out.println("[" + ts() + "] 记录启动时间：key = " + key + "，时间 = " + now);

            Boolean firstStart = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(now));
            Double score = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, code);
            if (Boolean.TRUE.equals(firstStart) && score != null) {
                // 起调成功：从等待队列移除，清理详情
                stringRedisTemplate.opsForZSet().remove(TASK_WAITING_ZSET, code);
                stringRedisTemplate.delete(TASK_INFO + code);
                System.out.println("[" + ts() + "] 启动时间已写入，已从等待队列移除");

                // 粗略估算终点节点忙到什么时候，给下一轮参考
                String from = instruction.getLocationFrom();
                String to   = instruction.getLocationTo();
                long travelMs = estimateTravelMs(from, to);
                long processMs = DEFAULT_PROCESS_MS;
                long availableAt = now + travelMs + processMs;

                String nodeKey = NODE_AVAILABLE_PREFIX + to.trim().toUpperCase();
                stringRedisTemplate.opsForValue().set(nodeKey, String.valueOf(availableAt));
            }
        });

        // 5) 日志与返回
        System.out.println("[" + getCurrentTimestamp() + "] 本轮调度结果：共 "
                + ordered.size() + " 条（ready=" + ready.size() + "，deferred=" + deferred.size() + "）");
        System.out.println("详情：");
        System.out.println(ordered);

        return Result.success(ordered, "获取成功");
    }

    // ===================== ETA + 节点占用判断 =====================

    /**
     * 在本轮调度的节点占用计划上判断：
     * 如果现在发这条任务，到终点时大致是否空闲。
     * 若可以发，则顺带把 nodePlan 里该终点的可用时间往后推一段。
     */
    private boolean canArriveWhenNodeFreeInPlan(InstructionExVO vo,
                                                long now,
                                                Map<String, Long> nodePlan) {
        String from = vo.getLocationFrom();
        String to   = vo.getLocationTo();
        if (from == null || to == null) {
            // 信息不完整时先不拦
            return true;
        }

        String nodeKey = to.trim().toUpperCase();

        // 1) 预估行驶时间 + ETA
        long travelMs = estimateTravelMs(from, to);
        long eta = now + travelMs;

        // 2) 真实的节点可用时间（上一轮留下的），从 Redis 读一次
        long realAvailable = getNodeAvailableAt(nodeKey, now);

        // 3) 本轮前面已经“预占”的时间（如果有就用 plan，没有就用 real）
        long planned = nodePlan.getOrDefault(nodeKey, realAvailable);

        long nodeAvailableAt = Math.max(realAvailable, planned);

        // 4) 如果 ETA + buffer 还早于可用时间，说明这趟去会在那边干等，先不发
        boolean ok = eta + SAFE_EARLY_ARRIVE_MS >= nodeAvailableAt;
        if (ok) {
            long processMs = DEFAULT_PROCESS_MS;
            long newAvailable = eta + processMs;
            nodePlan.put(nodeKey, newAvailable);
        }
        return ok;
    }

    /**
     * 读取终点预计“可接下一单”的时间，没有数据则视为“现在就可以”
     */
    private long getNodeAvailableAt(String node, long now) {
        if (node == null) return now;
        String key = NODE_AVAILABLE_PREFIX + node.trim().toUpperCase();
        String v = stringRedisTemplate.opsForValue().get(key);
        if (v == null) return now;
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return now;
        }
    }

    /**
     * 估算 from -> to 的行驶时间（毫秒），先看 OD 统计，没有就用默认
     */
    private long estimateTravelMs(String from, String to) {
        if (from == null || to == null) {
            return DEFAULT_TRAVEL_MS;
        }
        String key = "stats:od:" + from.trim().toUpperCase() + "|" + to.trim().toUpperCase();
        Object meanStr = stringRedisTemplate.opsForHash().get(key, "mean_ms");
        if (meanStr == null) {
            return DEFAULT_TRAVEL_MS;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(meanStr));
        } catch (Exception e) {
            return DEFAULT_TRAVEL_MS;
        }
    }

    // ===================== 调度核心：打分排序 =====================

    /**
     * 按优先级 + 等待时间 - 历史耗时 打分，直接排序
     */
    private void schedule(List<InstructionExVO> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();
        Map<String, TaskCtx> ctx = new HashMap<>(candidates.size());

        for (InstructionExVO vo : candidates) {
            String code = vo.getInstructionCode();
            Map<Object, Object> m = stringRedisTemplate.opsForHash().entries(TASK_INFO + code);

            long enqueueAt = 0L;
            if (m != null && m.get("enqueueAt") != null) {
                try {
                    enqueueAt = Long.parseLong(String.valueOf(m.get("enqueueAt")));
                } catch (Exception ignore) {}
            } else {
                throw new RuntimeException("缺少 enqueueAt：" + code);
            }
            long waitMin = Math.max(0, (now - enqueueAt) / 60000);

            int p = vo.getPriority() == null ? 0 : vo.getPriority();

            double histMs = historicalCostMs(vo.getLocationFrom(), vo.getLocationTo());

            double base = Wp * (double) p
                    + Ww * (double) waitMin
                    - Wt * histMs;

            ctx.put(code, new TaskCtx(vo, base, waitMin));
        }

        // 按得分从高到低排
        candidates.sort((a, b) -> {
            TaskCtx ca = ctx.get(a.getInstructionCode());
            TaskCtx cb = ctx.get(b.getInstructionCode());
            double sa = (ca == null ? 0 : ca.baseScore);
            double sb = (cb == null ? 0 : cb.baseScore);
            return Double.compare(sb, sa);
        });
    }

    /**
     * 历史耗时：从 stats:od:FROM|TO 里拿 mean/std/count，按“代价最低”组合
     */
    private double historicalCostMs(String from, String to) {
        List<String> fromAs = normalizeToGrayAnchors(from);
        List<String> toAs   = normalizeToGrayAnchors(to);
        if (fromAs.isEmpty() || toAs.isEmpty()) {
            // 映射不到主环，先不加惩罚
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
                double scale = Math.min(1.0, cnt / (double) Math.max(1, WARMUP_N));
                double cost = base * scale;

                if (cost < best) best = cost;
            }
        }
        return Double.isInfinite(best) ? 0.0 : best;
    }

    private static class TaskCtx {
        final InstructionExVO vo;
        final double baseScore;
        final long waitMin;

        TaskCtx(InstructionExVO vo, double baseScore, long waitMin) {
            this.vo = vo;
            this.baseScore = baseScore;
            this.waitMin = waitMin;
        }
    }

    // ===================== 取候选、清理等 =====================

    public List<InstructionExVO> getInstructionsBySchedule(int size) {
        if (size <= 0) return Collections.emptyList();

        // 从等待队列拿候选（分数高在前）
        Set<String> candidates = stringRedisTemplate.opsForZSet()
                .reverseRange(TASK_WAITING_ZSET, 0, -1);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> codes = new ArrayList<>(candidates);

        // pipeline 批量拿 Hash
        List<Object> results = stringRedisTemplate.executePipelined(
                new SessionCallback<Object>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Object execute(RedisOperations operations) throws DataAccessException {
                        RedisOperations<String, String> ops =
                                (RedisOperations<String, String>) operations;
                        for (String code : codes) {
                            ops.opsForHash().entries(TASK_INFO + code);
                        }
                        return null;
                    }
                }
        );

        int n = codes.size();
        List<InstructionExVO> list = new ArrayList<>(n);

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
                } else {
                    vo.setPriority(1);
                }
                vo.setContainerCode((String) m.get("containerCode"));
            }
            list.add(vo);
        }

        // 内存里按打分排一遍
        schedule(list);
        return list;
    }

    @Override
    public Result<Void> clear() {
        System.out.println();
        System.out.println("[" + getCurrentTimestamp() + "] clear 调用");
        String pattern = "*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        System.out.println("[" + getCurrentTimestamp() + "] Redis 已清空");
        return Result.success();
    }

    private String getCurrentTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return now.format(formatter);
    }

    // ===================== 环路建模（灰点 + 出入口映射） =====================

    public static class Seg {
        public final String id;  // 无向段 id
        public final int dir;    // +1 顺时针，-1 逆时针
        public Seg(String id, int dir) { this.id = id; this.dir = dir; }
        @Override public String toString(){ return "Seg{id="+id+",dir="+dir+"}"; }
    }

    public enum DirectionPolicy {
        SHORTEST, CLOCKWISE, COUNTERCLOCKWISE
    }

    private static final List<String> RING = List.of(
            "G01","G02","G03","G04","G05","G06","G07","G08","G09","G10",
            "G11","G12","G13","G14","G15","G16","G17","G18","G19","G20",
            "G21","G22","G23","G24","G25","G26","G27","G28","G29","G30",
            "G31","G32","G33","G34","G35","G36","G37","G38","G39","G40",
            "G41","G42","G43","G44","G45","G46","G47","G48","G49","G50",
            "G51","G52","G53","G54","G55","G56","G57","G58","G59","G60",
            "G61","G62","G63","G64","G65","G66","G67","G68"
    );

    private static final Map<String, List<String>> ATTACH = new HashMap<String, List<String>>() {{
        // 下排
        put("30", List.of("G68"));
        put("32", List.of("G66"));
        put("33", List.of("G65"));
        put("35", List.of("G63"));
        put("36", List.of("G62"));
        put("38", List.of("G62"));
        put("39", List.of("G59"));
        put("310", List.of("G57"));
        put("311", List.of("G56"));
        put("313", List.of("G54"));
        put("314", List.of("G53"));
        put("316", List.of("G51"));
        put("318", List.of("G49"));
        put("320", List.of("G47"));
        put("321", List.of("G46"));
        put("323", List.of("G44"));
        put("324", List.of("G43"));
        put("325", List.of("G41"));
        put("326", List.of("G40"));
        put("328", List.of("G38"));

        // 上排
        put("00", List.of("G01"));
        put("03", List.of("G04"));
        put("05", List.of("G06"));
        put("09", List.of("G10"));
        put("011", List.of("G12"));
        put("012", List.of("G13"));
        put("014", List.of("G15"));
        put("016", List.of("G17"));
        put("019", List.of("G20"));
        put("021", List.of("G22"));
        put("022", List.of("G23"));
        put("024", List.of("G25"));
        put("025", List.of("G26"));
        put("027", List.of("G28"));
        put("028", List.of("G29"));
    }};

    private static final Map<String,Integer> POS = new HashMap<>();
    static {
        for (int i = 0; i < RING.size(); i++) POS.put(RING.get(i).toUpperCase(), i);
    }

    private static List<String> normalizeToGrayAnchors(String raw) {
        if (raw == null) return Collections.emptyList();
        String s = raw.trim().toUpperCase();

        if (POS.containsKey(s)) return Collections.singletonList(s);

        List<String> anchors = ATTACH.get(s);
        if (anchors != null && !anchors.isEmpty()) {
            List<String> list = new ArrayList<>(anchors.size());
            for (String a : anchors) list.add(a.toUpperCase());
            return list;
        }
        return Collections.emptyList();
    }

    private static List<Seg> computePathSegments(String from, String to) {
        return computePathSegments(from, to, DirectionPolicy.SHORTEST);
    }

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

    private static List<Seg> pathBetweenGray(String fromGray, String toGray, DirectionPolicy policy) {
        Integer si = POS.get(fromGray);
        Integer ti = POS.get(toGray);
        if (si == null || ti == null || si.equals(ti)) {
            return Collections.emptyList();
        }
        int n = RING.size();

        List<Seg> cw = new ArrayList<>();
        for (int i = si; i != ti; i = (i + 1) % n) {
            int j = (i + 1) % n;
            String a = RING.get(i), b = RING.get(j);
            String undirected = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            cw.add(new Seg(undirected, +1));
        }

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
            default: return (ccw.size() < cw.size()) ? ccw : cw;
        }
    }

    public static void main(String[] args) {
        // 本地测试用，不连 Redis 也能跑 schedule 和路径展开
        InstructionServcieImpl svc = new InstructionServcieImpl(null);

        InstructionExVO t1 = new InstructionExVO();
        t1.setInstructionCode("T001");
        t1.setLocationFrom("IN1_EXIT");
        t1.setLocationTo("OUT1_ENTRY");
        t1.setPriority(3);

        InstructionExVO t2 = new InstructionExVO();
        t2.setInstructionCode("T002");
        t2.setLocationFrom("IN2_EXIT");
        t2.setLocationTo("OUT3_ENTRY");
        t2.setPriority(2);

        InstructionExVO t3 = new InstructionExVO();
        t3.setInstructionCode("T003");
        t3.setLocationFrom("G10");
        t3.setLocationTo("G20");
        t3.setPriority(1);

        List<InstructionExVO> list = new ArrayList<>();
        list.add(t1);
        list.add(t2);
        list.add(t3);

        System.out.println("== 路径展开 ==");
        for (InstructionExVO vo : list) {
            List<Seg> path = computePathSegments(vo.getLocationFrom(), vo.getLocationTo());
            System.out.println(vo.getInstructionCode() + " [" + vo.getLocationFrom() + " -> " + vo.getLocationTo() + "]");
            System.out.println("  段数=" + path.size() + "  详情=" + pathToString(path));
        }

        svc.schedule(list);

        System.out.println("\n== 调度顺序（高分在前） ==");
        for (int i = 0; i < list.size(); i++) {
            InstructionExVO vo = list.get(i);
            System.out.println((i + 1) + ". " + vo.getInstructionCode()
                    + " (p=" + (vo.getPriority() == null ? 0 : vo.getPriority())
                    + ", " + vo.getLocationFrom() + " -> " + vo.getLocationTo() + ")");
        }
    }

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

    private static String ts() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
