package com.example.zdtx.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.dto.instruction.InstructionQueryDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.InstructionServcie;
import com.example.zdtx.utils.HttpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    public Result<List<String>> getInstructions() {

        // 1) 读取当前运行中的任务（可能为空）
        Set<String> running = stringRedisTemplate.opsForZSet()
                .range(TASK_RUNNING_ZSET, 0, -1);
        if (running == null) running = java.util.Collections.emptySet();

        int runningSize = running.size();
        int slots = Math.max(0, MAX_TASK - runningSize);

        // 2) 如果还有空位，从等待队列按优先级原子弹出
        java.util.List<String> poppedCodes = java.util.Collections.emptyList();
        if (slots > 0) {
            var tuples = stringRedisTemplate.opsForZSet()
                    .popMax(TASK_WAITING_ZSET, slots);
            if (tuples != null && !tuples.isEmpty()) {
                poppedCodes = tuples.stream()
                        .map(org.springframework.data.redis.core.ZSetOperations.TypedTuple::getValue)
                        .filter(Objects::nonNull)
                        .toList();

                // 3) 将新弹出的任务加入 RUNNING 集合
                //    分数可用“开始时间戳”，便于后续按启动顺序查看
                long now = System.currentTimeMillis();
                for (int i = 0; i < poppedCodes.size(); i++) {
                    String code = poppedCodes.get(i);
                    stringRedisTemplate.opsForZSet()
                            .add(TASK_RUNNING_ZSET, code, now + i);
                }
            }
        }

        // 4) 结果：running ∪ popped（去重 Set 再转 List）
        LinkedHashSet<String> result = new LinkedHashSet<>(running);
        result.addAll(poppedCodes);

        if (result.isEmpty()) {
            return Result.success(java.util.List.of(), "暂无待执行的指令");
        }
        return Result.success(new ArrayList<>(result), "获取待执行指令成功");
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
