package com.skatingcoach.video;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="skating.video.worker-enabled",havingValue="true",matchIfMissing=true)
public class VideoWorker {
    private final VideoJobs jobs;
    private final FrameObserver observer;
    public VideoWorker(VideoJobs jobs,FrameObserver observer) { this.jobs=jobs; this.observer=observer; }
    @Scheduled(fixedDelayString="${skating.video.worker-delay-ms:1000}") public void tick() {
        if (observer.available()) jobs.processOne();
    }
}
