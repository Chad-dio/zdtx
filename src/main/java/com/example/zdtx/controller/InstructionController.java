package com.example.zdtx.controller;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.service.InstructionServcie;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/instruction")
@RequiredArgsConstructor
@CrossOrigin
public class InstructionController {

    private final InstructionServcie instructionServcie;

    @PostMapping ("/addInstruction")
    Result<Boolean> addInstruction(@Valid @RequestBody InstructionAddDTO requestparm){
        return instructionServcie.addInstruction(requestparm);
    }

    @PostMapping("/addInstructions")
    Result<Void> addInstructions(@Valid @RequestBody List<InstructionAddDTO> requestparm){
        return instructionServcie.addInstructions(requestparm);
    }

    @DeleteMapping("/cancelInstruction")
    Result<Void> cancelInstruction(@Valid @RequestBody InstructionCancelDTO requestparm){
        return instructionServcie.cancelInstruction(requestparm);
    }

    @GetMapping("/getInstructions")
    Result<List<String>> getInstructions(){
        return instructionServcie.getInstructions();
    }

    @DeleteMapping("/clear")
    Result<Void> clear(){
        return instructionServcie.clear();
    }
}
