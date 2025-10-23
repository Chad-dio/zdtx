package com.example.zdtx.service;

import com.example.zdtx.domain.dto.status.StatusUpdateDTO;
import com.example.zdtx.domain.entity.Result;

public interface StatisticsService {
    Result<Boolean> updateStatus(StatusUpdateDTO requestparm);
}
