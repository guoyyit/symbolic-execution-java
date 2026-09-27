package com.github.guoyyit.symbolicexecutionjava.service;

import com.github.guoyyit.symbolicexecutionjava.api.AnalysisRequest;
import com.github.guoyyit.symbolicexecutionjava.api.AnalysisResult;
import org.springframework.stereotype.Service;

/** Submits, tracks, cancels and resumes isolated analysis jobs. */
@Service
public class AnalysisService {

    public AnalysisResult submit(AnalysisRequest request) {
        return null;
    }

    public AnalysisResult getResult(String jobId) {
        return null;
    }

    public void cancel(String jobId) {
    }

    public AnalysisResult resume(String jobId) {
        return null;
    }
}
