package com.example.zdtx.domain.dto.instruction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstructionQueryDTO {
    @NotNull(message = "起点不能为空")
    private String locationFrom;

    @NotNull(message = "终点不能为空")
    private String locationTo;
}
