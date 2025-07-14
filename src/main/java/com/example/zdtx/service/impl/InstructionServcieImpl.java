package com.example.zdtx.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.InstructionServcie;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.zdtx.constants.RedisConstants.TASK_INFO;
import static com.example.zdtx.constants.RedisConstants.TASK_WAITING_ZSET;

@Service
@RequiredArgsConstructor
public class InstructionServcieImpl implements InstructionServcie {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result<Void> addInstruction(InstructionAddDTO requestparm) {
        if(requestparm == null){
            return Result.error("指令为空");
        }
        //指令进入排队队列
        stringRedisTemplate.opsForZSet().add(TASK_WAITING_ZSET,
                requestparm.getInstructionCode(),
                requestparm.getPriority());
        //指令的内容
        Map<String, Object> map = BeanUtil.beanToMap(
                requestparm,
                new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()    // 不写入 null
                        .setFieldNameEditor(name -> name)  // 保留原字段名
        );
        stringRedisTemplate.opsForHash().putAll(TASK_INFO + requestparm.getInstructionCode(),
                map);
        return Result.success();
    }

    @Override
    public Result<Void> addInstructions(List<InstructionAddDTO> requestparm) {
        if (requestparm == null || requestparm.isEmpty()) {
            return Result.success();
        }
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer keySer = new StringRedisSerializer();
            StringRedisSerializer valSer = new StringRedisSerializer();

            for (InstructionAddDTO dto : requestparm) {
                String code = dto.getInstructionCode();
                Integer score = dto.getPriority();

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
}
