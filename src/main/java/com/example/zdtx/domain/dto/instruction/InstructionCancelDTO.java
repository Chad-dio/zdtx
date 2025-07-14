package com.example.zdtx.domain.dto.instruction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstructionCancelDTO {
    @NotNull(message = "指令号不能为空")
    private String instructionCode;
}
