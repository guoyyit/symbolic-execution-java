package com.github.guoyyit.symbolicexecutionjava.service;

import com.github.guoyyit.symbolicexecutionjava.checkpoint.CheckpointStore;
import com.github.guoyyit.symbolicexecutionjava.checkpoint.FileCheckpointStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Spring wiring for analysis components. */
@Configuration
@EnableConfigurationProperties(ServiceConfig.class)
public class AnalysisConfiguration {

    @Bean
    public CheckpointStore checkpointStore(ServiceConfig config) {
        return new FileCheckpointStore(Path.of(config.checkpointDir()));
    }
}
