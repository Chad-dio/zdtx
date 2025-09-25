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
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.example.zdtx.constants.RedisConstants.*;

@Service
@RequiredArgsConstructor
public class InstructionServcieImpl implements InstructionServcie {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result<Boolean> addInstruction(InstructionAddDTO requestparm) {
        if (requestparm == null) {
            return Result.error("指令为空");
        }
        // 1) 进等待队列（score 用优先级）
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

        // 3) 写入 Hash（key、field、value 都是 String）
        String infoKey = TASK_INFO + requestparm.getInstructionCode();
        stringRedisTemplate.opsForHash().putAll(infoKey, strMap);

        return Result.success(Boolean.TRUE, "添加成功");
    }

    @Override
    public Result<Void> addInstructions(List<InstructionAddDTO> requestparm) {
        if (requestparm == null || requestparm.isEmpty()) {
            return Result.success();
        }
        //TODO:接收任务之后，是直接进行规划，
        // 还是先加入等待队列然后一个定时任务进行反馈
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer keySer = new StringRedisSerializer();
            StringRedisSerializer valSer = new StringRedisSerializer();

            for (InstructionAddDTO dto : requestparm) {
                String code = dto.getInstructionCode();
                //先优先级，再先来后到
                long now = System.currentTimeMillis();
                double score = dto.getPriority() * 1e13 - now;


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

                Map<byte[], byte[]> hash = new HashMap<>();
                map.forEach((k, v) -> {
                    hash.put(keySer.serialize(k),
                            valSer.serialize(v.toString()));
                });
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
        String instructionCode = requestparm.getInstructionCode();
        Double score = stringRedisTemplate.opsForZSet().score(TASK_WAITING_ZSET, instructionCode);
        if(score == null){
            return Result.error("指令已经启动");
        }
        stringRedisTemplate.opsForZSet().remove(TASK_WAITING_ZSET, instructionCode);
        String infoKey = TASK_INFO + instructionCode;
        stringRedisTemplate.delete(infoKey);
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
    public Result<List<InstructionExVO>> getInstructions() {
        List<InstructionExVO> instructions = getInstructionsBySchedule(MAX_TASK);
        if (instructions.isEmpty()) {
            return Result.success(Collections.emptyList(), "暂无待执行的指令");
        }
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

        // 2) pipeline 批量 HGETALL，结果就是 Map<Object,Object>，无需自己反序列化
        final List<Object> results = stringRedisTemplate.executePipelined(
                new org.springframework.data.redis.core.SessionCallback<Object>() {
                    @Override
                    public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                            throws org.springframework.dao.DataAccessException {
                        for (String code : codes) {
                            operations.opsForHash().entries(TASK_INFO + code);
                        }
                        return null; // 必须返回 null 才会把 pipeline 结果带出来
                    }
                }
        );

        // 安全兜底：results 可能为 null（极少数实现），做一致性处理
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

        // 3) 调度算法占位（在此就地排序，后续你替换为真实算法）
        schedule(list);

        // 4) 仅截取前 size 条返回（不修改、不删除 Redis）
        return list.size() > size ? list.subList(0, size) : list;
    }

    /** 调度算法占位：先按 priority 降序，再按 instructionCode 升序 */
    private void schedule(List<InstructionExVO> list) {
        list.sort((a, b) -> {
            int ap = a.getPriority() == null ? 0 : a.getPriority();
            int bp = b.getPriority() == null ? 0 : b.getPriority();
            int c = Integer.compare(bp, ap); // priority desc
            if (c != 0) return c;
            String ac = a.getInstructionCode() == null ? "" : a.getInstructionCode();
            String bc = b.getInstructionCode() == null ? "" : b.getInstructionCode();
            return ac.compareTo(bc); // code asc
        });
    }


    @Override
    public Result<Void> clear() {
        String pattern = "task:*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        return Result.success();
    }

}
