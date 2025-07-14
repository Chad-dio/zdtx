package com.example.zdtx.domain.dto.remote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskFeedbackDTO {
    private String systemCode;    // 可选
    private String houseCode;     // 可选
    private Object parameters;    // 可选

    @NotNull(message = "指令号不能为空")
    private String instructionCode;

    @NotNull(message = "容器号不能为空")
    private String containerCode;

    @NotNull(message = "指令反馈状态不能为空")
    private String feedbackStatus;  // "execute", "finish", "exception"
}
