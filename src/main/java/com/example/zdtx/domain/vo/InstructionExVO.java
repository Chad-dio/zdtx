package com.example.zdtx.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstructionExVO {
    private String instructionCode;

    private String locationFrom;

    private String locationTo;

    private Integer priority;
}
