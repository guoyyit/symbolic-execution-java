package com.github.guoyyit.symbolicexecutionjava.config;

import com.github.guoyyit.symbolicexecutionjava.task.TaskManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the TaskManager singleton, starts its workers, stops them on shutdown. */
@Configuration
public class TaskManagerConfig {

    @Bean
    public TaskManager taskManager(AnalysisProperties properties) {
        TaskManager manager = new TaskManager(properties);
        manager.start();
        return manager;
    }
}
