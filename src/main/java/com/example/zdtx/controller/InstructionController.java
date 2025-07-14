package com.example.zdtx.controller;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.InstructionServcie;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/instruction/")
@RequiredArgsConstructor
public class InstructionController {

    private final InstructionServcie instructionServcie;

    @RequestMapping("/addInstruction")
    Result<Void> addInstruction(@Valid @RequestBody InstructionAddDTO requestparm){
        return instructionServcie.addInstruction(requestparm);
    }

    @RequestMapping("/addInstructions")
    Result<Void> addInstructions(@Valid @RequestBody List<InstructionAddDTO> requestparm){
        return instructionServcie.addInstructions(requestparm);
    }

    @RequestMapping("/cancelInstruction")
    Result<Void> cancelInstruction(@Valid @RequestBody InstructionAddDTO requestparm){
        return instructionServcie.addInstruction(requestparm);
    }
}
