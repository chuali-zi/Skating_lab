package com.skatingcoach.video;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

public class FrameObserverTest {
    final ObjectMapper mapper=new ObjectMapper();
    @TempDir Path directory;
    public static byte[] syntheticClip(Path dir) throws Exception {
        Path clip=dir.resolve("moving-square.mp4");
        var process=new ProcessBuilder("ffmpeg","-nostdin","-loglevel","error","-y","-f","lavfi","-i",
                "color=navy:size=160x96:rate=12:duration=2","-f","lavfi","-i","color=white:size=24x24:rate=12:duration=2",
                "-filter_complex","overlay=x=12+45*t:y=30","-c:v","libx264","-pix_fmt","yuv420p","-threads","1",clip.toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return Files.readAllBytes(clip);
    }
    @Test void realDecoderAndHttpRoundTripValidateFrameReferences() throws Exception {
        var sampled=new VideoSampler(mapper).sample(syntheticClip(directory));
        assertThat(sampled.timestamps()).containsExactly(0.0,0.5,1.0,1.5);
        assertThat(sampled.maxGap()).isEqualTo(0.5);
        var received=new AtomicReference<JsonNode>();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var report=mapper.readTree("""
                {"observations":[{"frame_indices":[0,3],"confidence":"high","visible_evidence":"画面中的物体向右移动。"}],
                 "pending_review":["未见滑冰者，技术待核。"]}
                """);
        http.createContext("/responses",exchange->{
            received.set(mapper.readTree(exchange.getRequestBody()));
            var envelope=mapper.createObjectNode().put("id","synthetic-response").put("model","fixture").put("status","completed");
            envelope.putArray("output").addObject().put("type","message").putArray("content").addObject()
                    .put("type","output_text").put("text",report.toString());
            byte[] reply=mapper.writeValueAsBytes(envelope);
            exchange.sendResponseHeaders(200,reply.length);
            try(var out=exchange.getResponseBody()){out.write(reply);}
        });
        http.start();
        try {
            var vision=new FrameObserver(mapper,true,"fixture",URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/responses"));
            var result=vision.observe(sampled);
            assertThat(result.path("model_output")).isEqualTo(report);
            assertThat(received.get().path("store").booleanValue()).isFalse();
            assertThat(received.get().at("/input/0/content").findValuesAsText("image_url")).hasSize(4).allMatch(s->s.startsWith("data:image/jpeg;base64,"));
            var obs=(com.fasterxml.jackson.databind.node.ObjectNode)report.at("/observations/0");
            obs.putArray("frame_indices").add(3).add(1);
            assertThat(vision.valid(report,4)).isFalse();
            obs.put("visible_evidence","可见人物屈膝。");
            assertThat(FrameObserver.qualityFlags(report)).containsExactly("SUBJECT_REVIEW_REQUIRED");
            obs.put("visible_evidence","画面中的物体向右移动。");
            assertThat(FrameObserver.qualityFlags(report)).isEmpty();
            obs.putArray("frame_indices").add(4);
            assertThat(vision.valid(report,4)).isFalse();
            obs.putArray("frame_indices").add(0);
            obs.put("visible_evidence","3A 已成功，长期能力已确认。");
            assertThat(vision.valid(report,4)).isFalse();
            assertThatThrownBy(()->new VideoSampler(mapper).sample(new byte[128])).isInstanceOf(java.io.IOException.class);
        } finally { http.stop(0); }
    }
    @Test @EnabledIfEnvironmentVariable(named="SKATING_TEST_LIVE_LUNA",matches="1")
    void syntheticGeometryThroughConfiguredProvider() throws Exception {
        var vision=new FrameObserver(mapper,true,System.getenv("OPENAI_API_KEY"),URI.create("https://api.openai.com/v1/responses"));
        var sampled=new VideoSampler(mapper).sample(syntheticClip(directory));
        var result=vision.observe(sampled);
        assertThat(vision.valid(result.path("model_output"),sampled.frames().size())).isTrue();
        System.out.printf("Live geometry observation: model=%s frames=%d observations=%d pending=%d%n",result.path("model").asText(),
                sampled.frames().size(),result.at("/model_output/observations").size(),result.at("/model_output/pending_review").size());
    }
}
