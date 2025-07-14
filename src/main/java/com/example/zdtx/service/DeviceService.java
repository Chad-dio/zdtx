package com.example.zdtx.service;

import com.example.zdtx.domain.dto.remote.DeviceStatusDTO;

public interface DeviceService {
    Integer isEndpointResourceAvailable(DeviceStatusDTO requestparm);
}
