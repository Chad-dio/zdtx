package com.example.zdtx.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.zdtx.domain.dto.remote.DeviceStatusDTO;
import com.example.zdtx.service.DeviceService;
import com.example.zdtx.utils.HttpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Integer isEndpointResourceAvailable(DeviceStatusDTO requestparm) {
        String url = "xxxx";
        String s = HttpUtils.postForm(url, BeanUtil.beanToMap(
                requestparm,
                new HashMap<>(),
                CopyOptions.create().ignoreNullValue()
        ));
        JSONObject json = JSONUtil.parseObj(s);
        int code = json.getInt("responseCode");
        if (code != 0) {
            throw new RuntimeException("查询设备状态失败: " + json.getStr("responseMessage"));
        }

        return json.getInt("status");
    }
}
