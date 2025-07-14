package com.example.zdtx.controller;

import com.example.zdtx.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/device/")
@RequiredArgsConstructor
public class DeviceController {
    private final DeviceService deviceService;


}
