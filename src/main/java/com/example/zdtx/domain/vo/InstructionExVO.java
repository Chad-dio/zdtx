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

    private String containerCode;

    @Override
    public String toString() {
        return "指令信息 {" +
                "指令号='" + instructionCode + '\'' +
                ", 起点='" + locationFrom + '\'' +
                ", 终点='" + locationTo + '\'' +
                ", 优先级=" + priority +
                '}' + '\n';
    }
}
