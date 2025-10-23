package com.example.zdtx.controller;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.status.StatusUpdateDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/status")
@RequiredArgsConstructor
@CrossOrigin
public class StatisticsController {

    private final StatisticsService statisticsService;

    @PostMapping("/update")
    Result<Boolean> updateStatus(@Valid @RequestBody StatusUpdateDTO requestparm){
        return statisticsService.updateStatus(requestparm);
    }

}
