package com.example.zdtx.domain.dto.remote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DeviceStatusDTO {
    private String systemCode;    // 可选
    private String houseCode;     // 可选
    private Object parameters;    // 可选

    @NotNull(message = "设备号不能为空")
    private String deviceCode;
}
