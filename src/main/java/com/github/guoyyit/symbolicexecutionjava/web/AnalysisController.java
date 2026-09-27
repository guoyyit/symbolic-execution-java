package com.github.guoyyit.symbolicexecutionjava.web;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisRequest;
import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import com.github.guoyyit.symbolicexecutionjava.service.AnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for symbolic-execution analysis jobs. */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping
    public AnalysisResult submit(@RequestBody AnalysisRequest request) {
        return service.submit(request);
    }

    @GetMapping("/{jobId}")
    public AnalysisResult get(@PathVariable String jobId) {
        return service.getResult(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public AnalysisResult cancel(@PathVariable String jobId) {
        service.cancel(jobId);
        return service.getResult(jobId);
    }

    @PostMapping("/{jobId}/resume")
    public AnalysisResult resume(@PathVariable String jobId) {
        return service.resume(jobId);
    }
}
