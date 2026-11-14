package com.skatingcoach.knowledge;

import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.persistence.TenantJdbc;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="skating.knowledge.worker-enabled",havingValue="true",matchIfMissing=true)
public class KnowledgeWorker {
    private final JdbcTemplate jdbc;private final TenantJdbc tenant;private final SourceStorage storage;
    private final DocumentParser parser;private final KnowledgeService knowledge;private final EmbeddingGateway embedding;
    public KnowledgeWorker(JdbcTemplate jdbc,TenantJdbc tenant,SourceStorage storage,DocumentParser parser,
                           KnowledgeService knowledge,EmbeddingGateway embedding) {
        this.jdbc=jdbc;this.tenant=tenant;this.storage=storage;this.parser=parser;this.knowledge=knowledge;this.embedding=embedding;
    }
    @Scheduled(fixedDelayString="${skating.knowledge.worker-delay-ms:2000}")
    public void tick() {
        var jobs=jdbc.queryForList("""
            update knowledge_job set status=case when status='cleanup' then 'cleanup' else 'running' end,
              lease_until=now()+interval '10 minutes',attempts=attempts+1
            where id=(select id from knowledge_job
              where ((status in ('pending','cleanup') and (lease_until is null or lease_until<now()))
                 or (status='running' and lease_until<now())) and (status='cleanup' or attempts<4)
              order by created_at for update skip locked limit 1)
            returning *
            """);
        if(jobs.isEmpty())return;
        var job=jobs.get(0);UUID sourceId=(UUID)job.get("source_id");
        CurrentActor actor=new CurrentActor((UUID)job.get("actor_id"),(UUID)job.get("athlete_id"));
        try {
            var rows=tenant.read(actor,()->jdbc.queryForList("select * from knowledge_source where id=?",sourceId));
            if(rows.isEmpty()){finish(sourceId,"deleted",null,job.get("lease_until"));return;}
            var source=rows.get(0);
            if("deleted".equals(source.get("status"))) {
                storage.delete((String)source.get("storage_key"));
                tenant.write(actor,()-> {jdbc.update("update knowledge_source set storage_key=null where id=?",sourceId);return null;});
                finish(sourceId,"deleted",null,job.get("lease_until"));return;
            }
            if("superseded".equals(source.get("status"))){finish(sourceId,"superseded",null,job.get("lease_until"));return;}
            if(source.get("storage_key")!=null && !"ready".equals(source.get("status")) && !"partial".equals(source.get("status"))) {
                List<KnowledgeModels.ParsedPart> parts;
                try(var input=storage.open((String)source.get("storage_key"))){parts=parser.parse(input);}
                tenant.write(actor,()-> {
                    String current=jdbc.queryForObject("select status from knowledge_source where id=?",String.class,sourceId);
                    if(Set.of("deleted","superseded").contains(current))return null;
                    knowledge.replaceBlocks(sourceId,parts);
                    jdbc.update("update knowledge_source set status=?,error_code=?,updated_at=now() where id=?",
                            parts.isEmpty()?"awaiting_visual":"partial",parts.isEmpty()?"VISUAL_PROVIDER_REQUIRED":"LAYOUT_REVIEW_REQUIRED",sourceId);
                    knowledge.bump(actor);return null;
                });
            }
            if(embedding.available()) {
                var blocks=tenant.read(actor,()->jdbc.queryForList("select b.id,b.text from knowledge_block b join knowledge_source s on s.id=b.source_id where s.id=? and s.status in ('ready','partial') and (b.embedding is null or b.embedding_model<>?) order by b.ordinal",sourceId,embedding.model()));
                for(int start=0;start<blocks.size();start+=16) {
                    var batch=blocks.subList(start,Math.min(start+16,blocks.size()));
                    var vectors=embedding.embed(batch.stream().map(b->(String)b.get("text")).toList());
                    tenant.write(actor,()-> {
                        String status=jdbc.queryForObject("select status from knowledge_source where id=?",String.class,sourceId);
                        if(!Set.of("ready","partial").contains(status))return null;
                        for(int i=0;i<batch.size();i++)jdbc.update("update knowledge_block set embedding=?::vector,embedding_model=?,embedding_dimensions=? where id=? and source_id=?",
                                EmbeddingGateway.literal(vectors.get(i)),embedding.model(),vectors.get(i).length,batch.get(i).get("id"),sourceId);
                        return null;
                    });
                }
            }
            finish(sourceId,"done",null,job.get("lease_until"));
        } catch(Exception e) {
            // Never record provider response bodies or private parser input in operational logs.
            finish(sourceId,"cleanup".equals(job.get("status"))?"cleanup":((Number)job.get("attempts")).intValue()>=4?"failed":"pending","PROCESSING_FAILED",job.get("lease_until"));
            tenant.write(actor,()-> {jdbc.update("update knowledge_source set status=case when status in ('queued','processing') then 'failed' else status end,error_code='PROCESSING_FAILED' where id=? and status not in ('deleted','superseded')",sourceId);return null;});
        }
    }
    private void finish(UUID source,String status,String code,Object lease){jdbc.update("update knowledge_job set status=?,error_code=?,lease_until=case when ?='cleanup' then now()+interval '1 minute' else null end where source_id=? and lease_until=? and status in ('running','cleanup')",status,code,status,source,lease);}
}
