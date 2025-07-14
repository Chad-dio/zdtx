package com.example.zdtx.service.impl;

import com.example.zdtx.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {
    private final StringRedisTemplate stringRedisTemplate;


}
