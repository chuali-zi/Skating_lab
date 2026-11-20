package com.skatingcoach.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class VideoSampler {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FRAMES = 20;
    public static final double INTERVAL = 0.5;
    private static final Set<String> BRANDS = Set.of("isom","iso2","iso4","iso5","iso6","mp41","mp42","avc1");
    private static final Pattern PTS = Pattern.compile("\\bn:\\s*\\d+\\s+pts:\\s*-?\\d+\\s+pts_time:(\\S+)");
    private static final List<String> MOV = List.of("-protocol_whitelist","file","-f","mov","-enable_drefs","0");
    private final ObjectMapper json;

    public record Frame(double seconds, byte[] jpeg) {}
    public record Clip(double duration, List<Frame> frames) {
        public List<Double> timestamps() { return frames.stream().map(Frame::seconds).toList(); }
        public double maxGap() {
            double next = duration, largest = 0;
            for (int i = frames.size() - 1; i >= 0; i--) {
                double t = frames.get(i).seconds();
                largest = Math.max(largest, next - t);
                next = t;
            }
            return Math.round(largest * 1_000_000) / 1_000_000.0;
        }
    }

    public VideoSampler(ObjectMapper json) { this.json = json; }

    public static boolean hasMp4Header(byte[] bytes) {
        if (bytes.length < 16 || !brand(bytes,4).equals("ftyp")) return false;
        int size = ByteBuffer.wrap(bytes).getInt();
        if (size < 16 || size > Math.min(1024, bytes.length) || size % 4 != 0) return false;
        if (BRANDS.contains(brand(bytes,8))) return true;
        for (int i = 16; i < size; i += 4) if (BRANDS.contains(brand(bytes,i))) return true;
        return false;
    }

    private static String brand(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    public boolean available() {
        try {
            run(List.of("ffprobe","-version"), 3);
            run(List.of("ffmpeg","-version"), 3);
            return true;
        } catch (IOException e) { return false; }
    }

    public Clip sample(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES || !hasMp4Header(bytes)) throw new IOException("请提供有效的 MP4 文件。");
        Path directory = Files.createTempDirectory("skating-frames-");
        try {
            Path source = Files.write(directory.resolve("clip.mp4"), bytes);
            var probe = new ArrayList<>(List.of("ffprobe","-v","error"));
            probe.addAll(MOV);
            probe.addAll(List.of("-show_entries",
                    "format=duration:stream=codec_type,codec_name,width,height,duration:stream_disposition=attached_pic",
                    "-of","json", source.toString()));
            JsonNode meta = json.readTree(run(probe,5));
            var tracks = meta.path("streams").findParents("codec_type").stream()
                    .filter(t -> t.path("codec_type").asText().equals("video")).toList();
            if (tracks.size() != 1 || tracks.get(0).path("disposition").path("attached_pic").asInt(1) != 0)
                throw new IOException("MP4 须包含一条视频轨。");
            JsonNode track = tracks.get(0);
            if (!track.path("codec_name").asText().equals("h264")) throw new IOException("视频轨须使用 H.264。");
            double duration = duration(track);
            duration(meta.path("format"));
            int w = track.path("width").asInt(), h = track.path("height").asInt();
            if (Math.min(w,h) < 2 || Math.max(w,h) > 1920 || (long) w*h > 1920L*1080)
                throw new IOException("画面长边最多 1920 像素，总像素最多 1920 × 1080。");

            var decode = new ArrayList<>(List.of("ffmpeg","-hide_banner","-nostdin","-loglevel","info","-xerror",
                    "-max_alloc","67108864","-threads","1","-filter_threads","1"));
            decode.addAll(MOV);
            decode.addAll(List.of("-i",source.toString(),"-map","0:v:0","-an","-sn","-dn","-vf",
                    "setpts=PTS-STARTPTS,select='isnan(prev_selected_t)+gte(t-prev_selected_t,0.5)',scale=512:512:force_original_aspect_ratio=decrease,showinfo",
                    "-fps_mode","passthrough","-frames:v",String.valueOf(MAX_FRAMES),"-threads","1","-q:v","3",
                    directory.resolve("%02d.jpg").toString()));
            var matches = PTS.matcher(run(decode,15));
            var frames = new ArrayList<Frame>();
            double previous = -1;
            while (matches.find()) {
                double t = Double.parseDouble(matches.group(1));
                if (!Double.isFinite(t) || t <= previous || t >= duration || (frames.isEmpty() && t != 0) || frames.size() == MAX_FRAMES)
                    throw new IOException("采样帧时间戳异常。");
                Path image = directory.resolve("%02d.jpg".formatted(frames.size()+1));
                frames.add(new Frame(t, Files.readAllBytes(image)));
                previous = t;
            }
            if (frames.size() < 2 || Files.exists(directory.resolve("%02d.jpg".formatted(frames.size()+1))))
                throw new IOException("有效采样帧不足或时间戳异常。");
            return new Clip(duration, List.copyOf(frames));
        } finally {
            try (var entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static double duration(JsonNode node) throws IOException {
        double seconds = node.path("duration").asDouble(Double.NaN);
        if (!(seconds >= 1 && seconds <= 10)) throw new IOException("视频轨及文件时长须为 1–10 秒。");
        return seconds;
    }

    private static String run(List<String> args, int seconds) throws IOException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new FutureTask<>(() -> {
            try (var stream = process.getInputStream()) {
                byte[] data = stream.readNBytes(1_048_577);
                if (data.length > 1_048_576) throw new IOException("解码器输出超限。");
                return new String(data, StandardCharsets.UTF_8);
            }
        });
        Thread reader = new Thread(output, "video-decoder-output");
        reader.setDaemon(true);
        reader.start();
        try {
            String result = output.get(seconds, TimeUnit.SECONDS);
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("无法解码片段。");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解码已中断。", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("无法在限制内解码片段。", e);
        } finally {
            process.destroyForcibly();
        }
    }
}
