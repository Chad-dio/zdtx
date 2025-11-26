package com.example.zdtx.domain.dto.instruction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstructionAddDTO {
    @NotNull(message = "指令号不能为空")
    private String instructionCode;

    @NotNull(message = "容器号不能为空")
    private String containerCode;

    @NotNull(message = "起点不能为空")
    private String locationFrom;

    @NotNull(message = "终点不能为空")
    private String locationTo;

    @NotNull(message = "优先级不能为空")
    private Integer priority;


    @Override
    public String toString() {
        return "指令信息 {" +
                "指令号='" + instructionCode + '\'' +
                ", 起点='" + locationFrom + '\'' +
                ", 终点='" + locationTo + '\'' +
                ", 优先级=" + priority +
                '}';
    }
}
