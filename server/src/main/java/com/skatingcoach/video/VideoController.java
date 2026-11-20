package com.skatingcoach.video;

import com.skatingcoach.access.CurrentActorService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assets/video-jobs")
public class VideoController {
    private final VideoJobs jobs;
    private final CurrentActorService actors;
    public VideoController(VideoJobs jobs,CurrentActorService actors) { this.jobs=jobs; this.actors=actors; }
    @GetMapping public Map<String,Object> list() { return jobs.index(actors.requireActor()); }
    @PostMapping(consumes="video/mp4") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> upload(HttpServletRequest request) throws IOException {
        var actor=actors.requireActor();
        jobs.requireConsent(request.getHeader("X-Skating-Frame-Consent"));
        if (request.getContentLengthLong()>VideoSampler.MAX_BYTES) throw VideoJobs.tooLarge();
        return jobs.enqueue(actor,VideoJobs.readClip(request.getInputStream()));
    }
    @PostMapping("/from-source/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> fromSource(@PathVariable UUID id,HttpServletRequest request) {
        var actor=actors.requireActor();
        jobs.requireConsent(request.getHeader("X-Skating-Frame-Consent"));
        return jobs.fromSource(actor,id);
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id) { return jobs.detail(actors.requireActor(),id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { jobs.delete(actors.requireActor(),id); }
    @GetMapping("/{id}/frames/{index}")
    public ResponseEntity<byte[]> frame(@PathVariable UUID id,@PathVariable int index) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(jobs.frame(actors.requireActor(),id,index));
    }
    @PutMapping("/{id}/observations/{index}/review")
    public Map<String,Object> review(@PathVariable UUID id,@PathVariable int index,@RequestBody com.fasterxml.jackson.databind.JsonNode review) {
        return jobs.review(actors.requireActor(),id,index,VideoJobs.readReview(review));
    }
}
