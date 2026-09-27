package com.github.guoyyit.symbolicexecutionjava.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisApiIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("analysis.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("analysis.workers", () -> 2);
        registry.add("analysis.quantum-states", () -> 64);
        registry.add("analysis.default-timeout-ms", () -> 30000);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_PROGRAM = """
            {"variables":[{"name":"x","input":true}],"statements":[
              {"kind":"assert","line":1,"condition":{"kind":"binary","op":"ge",
                "left":{"kind":"var","name":"x"},"right":{"kind":"const","value":0}}}
            ]}
            """;

    private static final String UNDEFINED_VAR_PROGRAM = """
            {"variables":[],"statements":[
              {"kind":"assign","line":3,"target":"y",
               "expression":{"kind":"const","value":1}}]}
            """;

    private static final String MISSING_BOUND_PROGRAM = """
            {"variables":[{"name":"i"}],"statements":[
              {"kind":"loop","line":5,"condition":{"kind":"binary","op":"lt",
                "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":3}},
                "body":[]}]}
            """;

    @Test
    void submitsAndPollsToCompletedResult() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"program\":" + VALID_PROGRAM + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andReturn();
        String taskId = com.jayway.jsonpath.JsonPath
                .read(submitted.getResponse().getContentAsString(), "$.taskId");

        long deadline = System.currentTimeMillis() + 10_000;
        String taskStatus;
        do {
            Thread.sleep(20);
            MvcResult poll = mockMvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk()).andReturn();
            taskStatus = com.jayway.jsonpath.JsonPath
                    .read(poll.getResponse().getContentAsString(), "$.status");
        } while ("QUEUED".equals(taskStatus) || "RUNNING".equals(taskStatus));

        org.assertj.core.api.Assertions.assertThat(taskStatus).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.findings[0].pathId").value(0))
                .andExpect(jsonPath("$.result.findings[0].kind").value("ASSERT_VIOLATED"))
                .andExpect(jsonPath("$.result.findings[0].model.x").value(-1))
                .andExpect(jsonPath("$.result.findings[0].line").value(1));
    }

    @Test
    void rejectsUndefinedVariableWithLocation() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"program\":" + UNDEFINED_VAR_PROGRAM + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("undefined variable")))
                .andExpect(jsonPath("$.location").value(
                        org.hamcrest.Matchers.containsString("(line 3)")));
    }

    @Test
    void rejectsMissingLoopBoundWithLineLocation() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"program\":" + MISSING_BOUND_PROGRAM + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("loop bound missing")))
                .andExpect(jsonPath("$.location").value(
                        org.hamcrest.Matchers.containsString("$.statements[0]")))
                .andExpect(jsonPath("$.location").value(
                        org.hamcrest.Matchers.containsString("(line 5)")));
    }

    @Test
    void returnsNotFoundForUnknownTask() throws Exception {
        mockMvc.perform(get("/api/tasks/does-not-exist"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tasks/does-not-exist/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelsLongRunningTaskWithoutResult() throws Exception {
        String loop = """
                {"variables":[{"name":"i"}],"statements":[
                  {"kind":"assign","target":"i","expression":{"kind":"const","value":0}},
                  {"kind":"loop","line":1,"condition":{"kind":"binary","op":"lt",
                    "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":999999999}},
                    "maxIterations":100000000,"body":[
                      {"kind":"assign","target":"i","expression":{"kind":"binary","op":"add",
                        "left":{"kind":"var","name":"i"},"right":{"kind":"const","value":1}}}]}
                ]}
                """;
        MvcResult submitted = mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"program\":" + loop + "}"))
                .andExpect(status().isAccepted()).andReturn();
        String taskId = com.jayway.jsonpath.JsonPath
                .read(submitted.getResponse().getContentAsString(), "$.taskId");

        long deadline = System.currentTimeMillis() + 5_000;
        String status;
        do {
            status = com.jayway.jsonpath.JsonPath.read(
                    mockMvc.perform(get("/api/tasks/" + taskId)).andReturn()
                            .getResponse().getContentAsString(), "$.status");
        } while ("QUEUED".equals(status) && System.currentTimeMillis() < deadline);

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel"))
                .andExpect(status().isAccepted());

        deadline = System.currentTimeMillis() + 10_000;
        do {
            Thread.sleep(20);
            status = com.jayway.jsonpath.JsonPath.read(
                    mockMvc.perform(get("/api/tasks/" + taskId)).andReturn()
                            .getResponse().getContentAsString(), "$.status");
        } while (!"CANCELLED".equals(status) && System.currentTimeMillis() < deadline);
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("CANCELLED");
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.nullValue()));
    }
}
