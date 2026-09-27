package com.github.guoyyit.symbolicexecutionjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisControllerTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void submitsAndFetchesResult() throws Exception {
        String body = "{\"program\":\"int x; assert(x == 0);\",\"seed\":0}";
        MvcResult res = mvc.perform(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String jobId = json.readTree(res.getResponse().getContentAsString()).get("jobId").asText();

        // poll until completed
        JsonNode result = null;
        for (int i = 0; i < 200; i++) {
            MvcResult g = mvc.perform(get("/api/analyses/" + jobId)).andReturn();
            result = json.readTree(g.getResponse().getContentAsString());
            if (!"QUEUED".equals(result.get("status").asText())
                    && !"RUNNING".equals(result.get("status").asText())) {
                break;
            }
            Thread.sleep(10);
        }
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", result.get("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(result.get("totalPaths").asInt() >= 2);
    }

    @Test
    void rejectsUndefinedVariableWithHttp400AndPosition() throws Exception {
        String body = "{\"program\":\"int x = z;\"}";
        mvc.perform(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "undefined variable 'z'")))
                .andExpect(jsonPath("$.position.line").value(1))
                .andExpect(jsonPath("$.position.column").value(9));
    }

    @Test
    void unknownJobGives404() throws Exception {
        mvc.perform(get("/api/analyses/nope"))
                .andExpect(status().isNotFound());
    }
}
