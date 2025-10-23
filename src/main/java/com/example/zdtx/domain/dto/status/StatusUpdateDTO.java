package com.example.zdtx.domain.dto.status;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatusUpdateDTO {
    @NotNull(message = "指令号不能为空")
    private String instructionCode;

    @NotNull(message = "起点不能为空")
    private String locationFrom;

    @NotNull(message = "终点不能为空")
    private String locationTo;

    private Date time;
}
