package com.skatingcoach.video;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.knowledge.SourceStorage;
import com.skatingcoach.knowledge.Evidence;
import com.skatingcoach.persistence.TenantJdbc;
import java.io.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VideoJobs {
    static final Map<String,Object> METHOD = Map.of("mode","sampled_frames","native_video",false,"audio_sent",false,
            "label","按采样帧理解","time_precision","帧间动作与准确发生时间待核。");
    static final Map<String,Object> LIMITS = Map.of("mime_type","video/mp4","codec","h264","max_bytes",VideoSampler.MAX_BYTES,
            "min_seconds",1,"max_seconds",10,"max_input_pixels",1920*1080,"max_input_side",1920,
            "min_frames",2,"max_frames",20,"sampling_interval_seconds",0.5);
    private final TenantJdbc tenant;
    private final JdbcTemplate db;
    private final SourceStorage storage;
    private final VideoSampler sampler;
    private final FrameObserver observer;
    private final ObjectMapper json;

    public VideoJobs(TenantJdbc tenant, JdbcTemplate db, SourceStorage storage, VideoSampler sampler, FrameObserver observer, ObjectMapper json) {
        this.tenant=tenant; this.db=db; this.storage=storage; this.sampler=sampler; this.observer=observer; this.json=json;
    }

    public Map<String,Object> index(CurrentActor actor) {
        var jobs=tenant.read(actor,()->db.queryForList("""
                select id,status,created_at from video_dispatch where athlete_id=? order by created_at desc,id desc limit 50
                """,actor.athleteId()));
        var limits=new LinkedHashMap<>(LIMITS);
        limits.put("max_frame_side",512);
        return Map.of("method",METHOD,"limits",limits,"model",FrameObserver.MODEL,
                "submission_enabled",observer.available() && sampler.available(),"jobs",jobs);
    }

    public void requireConsent(String consent) {
        if (!"1".equals(consent)) throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"FRAME_CONSENT","请明确同意将采样帧发送至 OpenAI。");
        if (!observer.available() || !sampler.available()) throw problem(HttpStatus.SERVICE_UNAVAILABLE,"VIDEO_DISABLED","请配置视频服务、模型密钥及 FFmpeg。");
    }

    static byte[] readClip(InputStream input) throws IOException {
        byte[] bytes=input.readNBytes(VideoSampler.MAX_BYTES+1);
        if (bytes.length>VideoSampler.MAX_BYTES) throw tooLarge();
        if (!VideoSampler.hasMp4Header(bytes)) throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_MP4","文件头不符合 MP4 格式。");
        return bytes;
    }

    public Map<String,Object> fromSource(CurrentActor actor,UUID source) {
        return tenant.write(actor,()->{
            var keys=db.queryForList("""
                    select storage_key from knowledge_source where id=? and athlete_id=? and storage_key is not null
                    and status not in ('deleted','superseded') for update
                    """,String.class,source,actor.athleteId());
            if (keys.isEmpty()) throw missing();
            try (var input=storage.open(keys.get(0))) { return enqueueInside(actor,readClip(input),source); }
            catch (IOException e) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"VIDEO_SOURCE_UNAVAILABLE","无法读取已保存的视频。"); }
        });
    }

    public Map<String,Object> enqueue(CurrentActor actor,byte[] bytes) {
        return tenant.write(actor,()->enqueueInside(actor,bytes,null));
    }

    private Map<String,Object> enqueueInside(CurrentActor actor,byte[] bytes,UUID source) {
        var row=db.queryForList("""
                insert into video_dispatch(id,actor_id,athlete_id,source_id,status) values(?,?,?,?,'queued')
                on conflict do nothing returning id,status,created_at
                """,UUID.randomUUID(),actor.actorId(),actor.athleteId(),source);
        if (row.isEmpty()) throw problem(HttpStatus.CONFLICT,"VIDEO_BUSY","已有视频正在排队或处理中。");
        var job=row.get(0);
        db.update("insert into video_evidence(id,athlete_id,clip) values(?,?,?)",job.get("id"),actor.athleteId(),bytes);
        job.put("method",METHOD);
        return job;
    }

    public Map<String,Object> detail(CurrentActor actor, UUID id) {
        return tenant.read(actor,()->detailInside(actor,id));
    }

    private Map<String,Object> detailInside(CurrentActor actor,UUID id) {
        var rows=db.queryForList("""
                select d.id,d.status,d.created_at,e.result::text as result,
                       case when d.status='failed' then coalesce(e.error,'处理已中断，请重新提交。') end as error
                from video_dispatch d join video_evidence e using(id,athlete_id) where d.id=? and d.athlete_id=?
                """,id,actor.athleteId());
        if (rows.isEmpty()) throw missing();
        var row=rows.get(0);
        row.put("method",METHOD);
        row.put("reviews",List.of());
        if (row.get("result")==null) return row;
        try {
            var raw=json.readTree((String)row.get("result"));
            var result=json.createObjectNode();
            for(String field:List.of("model","response_id","request_id","duration_seconds","max_sampling_gap_seconds","frame_timestamps_seconds","technical_assessment","model_output"))
                if(raw.has(field)) result.set(field,raw.get(field));
            row.put("result",result);
            var times=result.path("frame_timestamps_seconds");
            double prior=-1;
            boolean usable=times.isArray() && times.size()>=2 && times.size()<=20;
            for(var time:times) {
                double t=time.asDouble(Double.NaN);
                usable &= time.isNumber() && t>=0 && t<=10 && t>prior;
                prior=t;
            }
            if (!usable || !observer.valid(result.get("model_output"),times.size())) {
                result.set("model_output",json.valueToTree(Map.of("observations",List.of(),"pending_review",List.of("已有观察需人工核对。"))));
                if(!usable) result.putArray("frame_timestamps_seconds");
                result.put("quarantined",true);
                return row;
            }
            result.set("quality_flags",json.valueToTree(FrameObserver.qualityFlags(result.path("model_output"))));
        } catch(IOException | ClassCastException e) { throw problem(HttpStatus.CONFLICT,"VIDEO_REVIEW_REQUIRED","已存结果需要复核。"); }
        row.put("reviews",db.queryForList("""
                select id,observation_index,decision,capability_note,reviewed_at,revision from video_review
                where job_id=? and athlete_id=? order by observation_index
                """,id,actor.athleteId()));
        return row;
    }

    public byte[] frame(CurrentActor actor,UUID job,int index) {
        return tenant.read(actor,()->{
            var frames=db.queryForList("select jpeg from video_frame where job_id=? and idx=? and athlete_id=?",
                    byte[].class,job,index,actor.athleteId());
            if (frames.isEmpty()) throw missing();
            return frames.get(0);
        });
    }

    public record Review(String decision, Boolean confirmed, String capability_note) {}
    static Review readReview(JsonNode input) {
        if(input==null || !input.isObject() || !input.path("confirmed").isBoolean() || !input.path("decision").isTextual()
                || input.hasNonNull("capability_note") && !input.get("capability_note").isTextual())
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"VIDEO_REVIEW_INVALID","请明确确认复核操作与片段备注。");
        var fields=input.fieldNames();
        while(fields.hasNext()) if(!Set.of("decision","confirmed","capability_note").contains(fields.next()))
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"VIDEO_REVIEW_INVALID","复核请求含有未知字段。");
        return new Review(input.get("decision").asText(),input.get("confirmed").booleanValue(),input.path("capability_note").asText(null));
    }
    public record ReviewRef(UUID id, long revision) {
        public ReviewRef {
            if(id==null || revision<1) throw new IllegalArgumentException("复核引用缺少标识或版本。");
        }
    }

    public Evidence reviewedEvidence(CurrentActor actor,ReviewRef ref) {
        return tenant.read(actor,()->{
            var rows=db.queryForList("select job_id,observation_index,capability_note from video_review where id=? and athlete_id=? and revision=? and decision in ('accept','correct')",
                    ref.id(),actor.athleteId(),ref.revision());
            if(rows.isEmpty()) throw problem(HttpStatus.CONFLICT,"VIDEO_REVIEW_CHANGED","片段复核已变更，请重新选择。");
            var row=rows.get(0);
            UUID job=(UUID)row.get("job_id");
            int index=(Integer)row.get("observation_index");
            var detail=detailInside(actor,job);
            var report=(JsonNode)detail.get("result");
            var obs=report==null?null:report.at("/model_output/observations/"+index);
            if(obs==null || !obs.isObject()) throw problem(HttpStatus.CONFLICT,"VIDEO_REVIEW_CHANGED","该观察需要重新复核。");
            var times=new ArrayList<String>();
            for (var frame:obs.path("frame_indices")) times.add(report.path("frame_timestamps_seconds").get(frame.asInt()).asText()+" 秒");
            String caution=report.path("quality_flags").size()>0?" 模型对画面主体有疑问，请以人工对照本帧的备注为限。":"";
            return new Evidence(ref.id(),job,"本人复核的片段备注",row.get("capability_note")+"（仅限该片段，经人复核；长期能力、技术符号与分值待核。）"+caution,
                    "观察 "+(index+1)+" · "+String.join("、",times),null,ref.revision(),"reviewed_video");
        });
    }

    public Map<String,Object> review(CurrentActor actor,UUID job,int index,Review input) {
        String note=input.capability_note()==null?null:input.capability_note().strip();
        if (!Boolean.TRUE.equals(input.confirmed()) || input.decision()==null || !Set.of("accept","correct","ignore").contains(input.decision())
                || input.decision().equals("ignore")!=(note==null) || (note!=null && (note.isEmpty() || note.length()>400)))
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"VIDEO_REVIEW_INVALID","采纳或纠正须确认并填写 1–400 字的片段备注。忽略时留空。");
        return tenant.write(actor,()->{
            var detail=detailInside(actor,job);
            if (!"completed".equals(detail.get("status"))) throw problem(HttpStatus.CONFLICT,"VIDEO_PENDING","视频尚无可复核结果。");
            var result=(JsonNode)detail.get("result");
            if (result==null || index<0 || index>=result.at("/model_output/observations").size()) throw missing();
            var saved=db.queryForMap("""
                insert into video_review(id,job_id,athlete_id,observation_index,decision,capability_note) values(?,?,?,?,?,?)
                on conflict(job_id,observation_index) do update set decision=excluded.decision,capability_note=excluded.capability_note,
                    reviewed_at=now(),revision=video_review.revision+1
                returning id,observation_index,decision,capability_note,reviewed_at,revision
                """,UUID.randomUUID(),job,actor.athleteId(),index,input.decision(),note);
            if (((Number)saved.get("revision")).longValue()>1) withdrawReview(actor,(UUID)saved.get("id"));
            return saved;
        });
    }

    private void withdrawReview(CurrentActor actor,UUID review) {
        db.update("""
                update recommendation_run set request='{}',result=null,status='REDACTED',error_code='SOURCE_REVOKED',updated_at=now()
                where athlete_id=? and request#>>'{input,videoReview,id}'=?
                """,actor.athleteId(),review.toString());
        db.update("""
                update saved_plan set context_invalidated=true,revision=revision+1,updated_at=now(),
                    body=body - 'notes' - 'failureDescription' - 'videoReview'
                where athlete_id=? and body#>>'{videoReview,id}'=?
                """,actor.athleteId(),review.toString());
    }

    public void delete(CurrentActor actor,UUID id) {
        tenant.write(actor,()->{
            db.queryForList("select id from video_review where job_id=? and athlete_id=?",UUID.class,id,actor.athleteId())
                    .forEach(ref->withdrawReview(actor,ref));
            if(db.update("delete from video_dispatch where id=? and athlete_id=?",id,actor.athleteId())!=1) throw missing();
            return null;
        });
    }

    public boolean processOne() {
        // Expired in-flight requests have an unknown provider outcome. Only queued rows can be claimed.
        db.update("update video_dispatch set status='failed' where status='running' and started_at<now()-interval '5 minutes'");
        var candidates=db.queryForList("select id,actor_id,athlete_id from video_dispatch where status='queued' order by created_at,id limit 1");
        if (candidates.isEmpty()) return false;
        var row=candidates.get(0);
        var actor=new CurrentActor((UUID)row.get("actor_id"),(UUID)row.get("athlete_id"));
        UUID id=(UUID)row.get("id");
        byte[] bytes=tenant.write(actor,()->{
            if (db.update("update video_dispatch set status='running',started_at=now() where id=? and status='queued'",id)!=1) return null;
            var raw=db.queryForObject("select clip from video_evidence where id=?",byte[].class,id);
            db.update("update video_evidence set clip=null where id=?",id);
            return raw;
        });
        if (bytes==null) return false;
        String stage="decode";
        try {
            var clip=sampler.sample(bytes);
            stage="observe";
            var result=observer.observe(clip);
            stage="persist";
            tenant.write(actor,()->{
                if (db.update("update video_dispatch set status='completed' where id=? and status='running'",id)!=1) return null;
                for (int i=0;i<clip.frames().size();i++)
                    db.update("insert into video_frame(job_id,athlete_id,idx,jpeg) values(?,?,?,?)",id,actor.athleteId(),i,clip.frames().get(i).jpeg());
                db.update("update video_evidence set result=?::jsonb where id=?",result.toString(),id);
                return null;
            });
        } catch(Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason=switch(stage) {
                case "decode" -> "片段未通过解码或采样时间校验。";
                case "observe" -> e instanceof java.net.http.HttpTimeoutException ? "视频观察服务超时，本次结果未知。" :
                        "观察与帧证据无法核对。".equals(e.getMessage()) ? "模型返回的帧号或观察不符合约定，需重新核对。" :
                        "视频观察服务未返回完整且可验证的结果。";
                default -> "观察结果未能完整保存。";
            };
            tenant.write(actor,()->{
                if (db.update("update video_dispatch set status='failed' where id=? and status='running'",id)==1)
                    db.update("update video_evidence set error=? where id=?",reason,id);
                return null;
            });
        }
        return true;
    }

    static ApiException tooLarge() { return problem(HttpStatus.PAYLOAD_TOO_LARGE,"VIDEO_TOO_LARGE","视频最多 10 MiB。"); }
    private static ApiException missing() { return problem(HttpStatus.NOT_FOUND,"VIDEO_NOT_FOUND","未找到对应片段或观察。"); }
    private static ApiException problem(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
}
