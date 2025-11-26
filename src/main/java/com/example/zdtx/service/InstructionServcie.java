package com.example.zdtx.service;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.dto.instruction.InstructionQueryDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.domain.vo.InstructionExVO;

import java.util.List;

public interface InstructionServcie {
    Result<Boolean> addInstruction(InstructionAddDTO requestparm);

    Result<Void> addInstructions(List<InstructionAddDTO> requestparm);

    Result<String> cancelInstruction(InstructionCancelDTO requestparm);

    Boolean queryInstruction(InstructionQueryDTO requestparm);

    Result<List<InstructionExVO>> getInstructions() throws InterruptedException;

    Result<Void> clear();
}
