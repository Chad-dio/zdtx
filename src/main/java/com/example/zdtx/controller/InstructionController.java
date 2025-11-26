package com.example.zdtx.controller;

import com.example.zdtx.domain.dto.instruction.InstructionAddDTO;
import com.example.zdtx.domain.dto.instruction.InstructionCancelDTO;
import com.example.zdtx.domain.entity.Result;
import com.example.zdtx.domain.vo.InstructionExVO;
import com.example.zdtx.service.InstructionServcie;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/instruction")
@RequiredArgsConstructor
@CrossOrigin
public class InstructionController {

    private final InstructionServcie instructionServcie;

    // ⭐ 手动文件配置
    private static final Path TASK_LOG = Paths.get("logs", "instruction-tasks.log");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object FILE_LOCK = new Object();
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    @PostMapping("/addInstruction")
    Result<Boolean> addInstruction(@Valid @RequestBody InstructionAddDTO requestparm){
        long t0 = System.currentTimeMillis();

        Result<Boolean> result = instructionServcie.addInstruction(requestparm);

//        appendTaskLog(requestparm, result, System.currentTimeMillis() - t0);
        return result;
    }

    private void appendTaskLog(InstructionAddDTO req, Result<Boolean> resp, long costMs) {
        try {
            Files.createDirectories(TASK_LOG.getParent());

            ObjectNode node = MAPPER.createObjectNode();
            node.put("ts", TS.format(Instant.now()));
            node.put("api", "/instruction/addInstruction");
            node.put("ok", resp != null && Boolean.TRUE.equals(resp.getData()));
            node.put("costMs", costMs);
            node.set("request", MAPPER.valueToTree(req));
            node.set("response", MAPPER.valueToTree(resp));

            String line = MAPPER.writeValueAsString(node) + System.lineSeparator();

            synchronized (FILE_LOCK) {
                Files.writeString(TASK_LOG, line,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
        } catch (Exception e) {
            e.printStackTrace(); // 需要静默可去掉
        }
    }

    @PostMapping("/addInstructions")
    Result<Void> addInstructions(@Valid @RequestBody List<InstructionAddDTO> requestparm){
        return instructionServcie.addInstructions(requestparm);
    }

    @DeleteMapping("/cancelInstruction")
    Result<String> cancelInstruction(@Valid @RequestBody InstructionCancelDTO requestparm){
        return instructionServcie.cancelInstruction(requestparm);
    }

    @GetMapping("/getInstructions")
    Result<List<InstructionExVO>> getInstructions() throws InterruptedException {
        return instructionServcie.getInstructions();
    }

    @DeleteMapping("/clear")
    Result<Void> clear(){
        return instructionServcie.clear();
    }
}
