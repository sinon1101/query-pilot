package com.yang.dataagent.trace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 执行轨迹回放接口：列表按对话过滤，详情含全部步骤。
 * 第三阶段前端的"执行轨迹面板"消费这两个接口。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping
    public List<TraceService.TraceSummary> list(@RequestParam String conversationId) {
        return traceService.listByConversation(conversationId);
    }

    @GetMapping("/{id}")
    public TraceService.TraceView get(@PathVariable Long id) {
        return traceService.getTrace(id);
    }
}
