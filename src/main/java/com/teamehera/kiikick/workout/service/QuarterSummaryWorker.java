package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "kiikick.workout.summary-worker-enabled", matchIfMissing = true)
public class QuarterSummaryWorker {
    private static final Logger log = LoggerFactory.getLogger(QuarterSummaryWorker.class);
    private static final int BATCH_SIZE = 20;

    private final QuarterSummaryRepository summaries;
    private final QuarterSummaryCalculation calculation;

    public QuarterSummaryWorker(QuarterSummaryRepository summaries, QuarterSummaryCalculation calculation) {
        this.summaries = summaries;
        this.calculation = calculation;
    }

    // Polling committed PROCESSING rows also resumes jobs left behind by a stopped instance.
    @Scheduled(initialDelayString = "${kiikick.workout.summary-poll-ms:5000}",
            fixedDelayString = "${kiikick.workout.summary-poll-ms:5000}")
    public void poll() {
        for (var job : summaries.findProcessing(BATCH_SIZE)) {
            try {
                calculation.process(job);
            } catch (RuntimeException exception) {
                log.warn("Quarter summary processing will retry for {}", job.summary().quarterId(), exception);
            }
        }
    }
}
