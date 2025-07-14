package com.example.zdtx.service;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.entity.Result;

import java.util.List;

public interface InstructionServcie {
    Result<Void> addInstruction(InstructionAddDTO requestparm);

    Result<Void> addInstructions(List<InstructionAddDTO> requestparm);
}
