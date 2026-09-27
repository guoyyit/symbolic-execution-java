package com.github.guoyyit.symbolicexecutionjava.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.symbolicexecutionjava.api.dto.SubmitTaskRequest;
import com.github.guoyyit.symbolicexecutionjava.api.dto.SubmitTaskResponse;
import com.github.guoyyit.symbolicexecutionjava.api.dto.TaskView;
import com.github.guoyyit.symbolicexecutionjava.task.AnalysisTask;
import com.github.guoyyit.symbolicexecutionjava.task.TaskManager;
import com.github.guoyyit.symbolicexecutionjava.task.TaskSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST API for submitting and monitoring analysis tasks. */
@RestController
@RequestMapping("/api/tasks")
public class AnalysisController {

    private final TaskManager taskManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping
    public ResponseEntity<SubmitTaskResponse> submit(@RequestBody SubmitTaskRequest request) {
        if (request == null || request.program() == null) {
            throw new IllegalArgumentException("request body must contain a 'program' field");
        }
        String programJson;
        try {
            programJson = mapper.writeValueAsString(request.program());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("program must be a JSON value: " + e.getMessage());
        }
        TaskSpec spec = new TaskSpec(programJson, request.seed() == null ? 0L : request.seed(),
                request.timeoutMs(), request.maxStates(), request.maxPaths());
        String id = taskManager.submit(spec);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SubmitTaskResponse(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskView> get(@PathVariable String id) {
        return taskManager.get(id)
                .map(task -> ResponseEntity.ok(toView(task)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<TaskView> list() {
        return taskManager.list().stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        return taskManager.cancel(id)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.notFound().build();
    }

    private TaskView toView(AnalysisTask task) {
        return new TaskView(task.id(), task.status(), task.error(), task.result(),
                task.createdAt(), task.finishedAt());
    }
}
