package com.skatingcoach.knowledge;

import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.memory.MemoryService;
import com.skatingcoach.persistence.TenantJdbc;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.skatingcoach.knowledge.KnowledgeModels.*;

@Service
public class KnowledgeService {
    private static final String COMPETITION_FILTER="(s.competition_key is null or s.competition_key ~ '^[[:space:]]*$' or s.competition_key=?)";
    private final JdbcTemplate jdbc; private final TenantJdbc tenant;
    private final SourceStorage storage; private final EmbeddingGateway embeddings;
    private final MemoryService memory;
    public KnowledgeService(JdbcTemplate jdbc,TenantJdbc tenant,SourceStorage storage,
                            EmbeddingGateway embeddings,MemoryService memory) {
        this.jdbc=jdbc;this.tenant=tenant;this.storage=storage;this.embeddings=embeddings;this.memory=memory;
    }
    public List<Source> list(CurrentActor actor) {
        return tenant.read(actor,()->jdbc.query("select * from knowledge_source where status<>'deleted' order by created_at desc",KnowledgeService::map));
    }
    public Detail detail(CurrentActor actor,UUID id) {
        return tenant.read(actor,()->new Detail(getInside(id),jdbc.query("select id,text,locator from knowledge_block where source_id=? order by ordinal",
                (r,n)->new Block(r.getObject("id",UUID.class),r.getString("text"),r.getString("locator")),id)));
    }
    public Evidence evidence(CurrentActor actor,UUID id) {
        return tenant.read(actor,()-> {
            var rows=jdbc.query(evidenceSql()+" where b.id=? and s.status in ('ready','partial','superseded')",KnowledgeService::mapEvidence,id);
            if(rows.isEmpty())throw missing();return rows.get(0);
        });
    }
    public Source createText(CurrentActor actor,TextInput input) {
        if(input.text()==null||input.text().isBlank())throw new ApiException(HttpStatus.BAD_REQUEST,"EMPTY_DOCUMENT","请提供资料内容。");
        String competition=competitionKey(input.competitionKey());
        return tenant.write(actor,()-> {
            Source duplicate=findRequest(actor,input.requestId());
            if(duplicate!=null)return duplicate;
            UUID id=UUID.randomUUID(),family=id;long revision=1;
            if(input.supersedesId()!=null) {
                var previous=jdbc.queryForList("select family_id,revision from knowledge_source where id=? and athlete_id=? and status<>'deleted'",
                        input.supersedesId(),actor.athleteId());
                if(previous.isEmpty())throw missing();
                family=(UUID)previous.get(0).get("family_id");
                revision=jdbc.queryForObject("select max(revision)+1 from knowledge_source where family_id=?",Long.class,family);
                jdbc.update("update knowledge_source set status='superseded',updated_at=now() where family_id=? and status<>'deleted'",family);
            }
            jdbc.update("""
                insert into knowledge_source(id,athlete_id,actor_id,family_id,revision,title,source_kind,source_url,competition_key,status,request_id,raw_text,media_type)
                values(?,?,?,?,?,?,?, ?,?,'ready',?,?,'text/plain')
                """,id,actor.athleteId(),actor.actorId(),family,revision,title(input.title()),
                    competition==null?"user_document":"private_rule",input.sourceUrl(),competition,input.requestId(),input.text());
            replaceBlocks(id,TextProcessing.chunks(input.text(),"text"));
            enqueue(actor,id);bump(actor);
            return getInside(id);
        });
    }
    public Source upload(CurrentActor actor,String title,String mediaType,String competition,String requestId,InputStream input)throws IOException {
        return upload(actor,title,mediaType,competition,requestId,null,input);
    }
    public Source upload(CurrentActor actor,String title,String mediaType,String competition,String requestId,String sourceUrl,InputStream input)throws IOException {
        String scope=competitionKey(competition);
        Source existing=tenant.read(actor,()->findRequest(actor,requestId));
        if(existing!=null){input.close();return existing;}
        SourceStorage.Stored object=storage.put(input);
        try {
            return tenant.write(actor,()-> {
                UUID id=UUID.randomUUID();
                jdbc.update("""
                    insert into knowledge_source(id,athlete_id,actor_id,family_id,title,source_kind,competition_key,status,request_id,storage_key,media_type,content_hash,source_url)
                    values(?,?,?,?,?,?,?,'queued',?,?,?,?,?)
                    """,id,actor.athleteId(),actor.actorId(),id,title(title),scope==null?"user_document":"private_rule",
                        scope,requestId,object.key(),mediaType,object.hash(),sourceUrl);
                enqueue(actor,id);bump(actor);return getInside(id);
            });
        } catch(RuntimeException e){storage.delete(object.key());throw e;}
    }
    public Map<String,Object> delete(CurrentActor actor,UUID id) {
        String key=tenant.write(actor,()-> {
            var rows=jdbc.queryForList("select storage_key,status from knowledge_source where id=? and athlete_id=?",id,actor.athleteId());
            if(rows.isEmpty())throw missing();
            if("deleted".equals(rows.get(0).get("status")))return (String)rows.get(0).get("storage_key");
            jdbc.update("update knowledge_source set raw_text=null,status='deleted',updated_at=now() where id=?",id);
            jdbc.update("delete from knowledge_block where source_id=?",id);
            // Durable cleanup task reuses the source key without exposing document content.
            jdbc.update("update knowledge_job set status='cleanup',lease_until=null,attempts=0 where source_id=?",id);
            bump(actor);memory.invalidatePrivateDerivatives(actor);
            return (String)rows.get(0).get("storage_key");
        });
        try {
            storage.delete(key);
            tenant.write(actor,()-> {jdbc.update("update knowledge_source set storage_key=null where id=?",id);
                jdbc.update("update knowledge_job set status='deleted' where source_id=?",id);return null;});
            return Map.of("status","deleted");
        } catch(IOException e){return Map.of("status","excluded","cleanup","pending");}
    }
    public List<Map<String,Object>> catalog() {
        return jdbc.queryForList("select id,title,publisher,url,category,season,access_note as \"accessNote\",summary,verified_on as \"verifiedOn\",ready_for_scoring as \"readyForScoring\" from knowledge_catalog order by id");
    }
    public SearchResult search(CurrentActor actor,String query,String competition,int requestedLimit) {
        String scope=competitionKey(competition);
        int limit=Math.max(1,Math.min(requestedLimit,30));String q=query==null?"":query;
        double[] vector=null;List<String> warnings=new ArrayList<>();
        if(embeddings.available()) {
            try {vector=embeddings.embed(List.of(q)).get(0);}catch(RuntimeException e){warnings.add("语义检索暂不可用，已使用关键词和精确来源检索。");}
        } else warnings.add("尚未配置 Embedding，当前使用关键词检索；没有生成模拟向量。");
        final double[] queryVector=vector;
        List<Evidence> result=tenant.read(actor,()-> {
            String applicable=" s.status in ('ready','partial') and "+COMPETITION_FILTER+" ";
            String terms=TextProcessing.query(q);
            List<UUID> lexical=terms.isBlank()?List.of():jdbc.query("""
                select b.id from knowledge_block b join knowledge_source s on s.id=b.source_id where
                """+applicable+" and b.search_vector @@ to_tsquery('simple',?) order by ts_rank_cd(b.search_vector,to_tsquery('simple',?)) desc,b.id limit ?",
                    (r,n)->r.getObject(1,UUID.class),scope,terms,terms,limit*3);
            List<UUID> semantic=queryVector==null?List.of():jdbc.query("""
                select b.id from knowledge_block b join knowledge_source s on s.id=b.source_id where
                """+applicable+" and b.embedding_model=? and b.embedding_dimensions=? order by b.embedding <=> ?::vector,b.id limit ?",
                    (r,n)->r.getObject(1,UUID.class),scope,embeddings.model(),queryVector.length,EmbeddingGateway.literal(queryVector),limit*3);
            Map<UUID,Double> scores=new HashMap<>();
            for(List<UUID> ranking:List.of(lexical,semantic))for(int i=0;i<ranking.size();i++)scores.merge(ranking.get(i),1.0/(60+i+1),Double::sum);
            List<UUID> selected=scores.keySet().stream().sorted(Comparator.<UUID>comparingDouble(scores::get).reversed().thenComparing(UUID::toString)).limit(limit).toList();
            List<Evidence> hits=new ArrayList<>();
            for(UUID id:selected) {
                // Hydrate from current authoritative source only; never rerank cached private plaintext.
                hits.addAll(jdbc.query(evidenceSql()+" where b.id=? and "+applicable,KnowledgeService::mapEvidence,id,scope));
            }
            return List.copyOf(hits);
        });
        return new SearchResult(vector==null?"lexical":"hybrid",result,warnings);
    }
    public List<Evidence> mandatory(CurrentActor actor,String competition) {
        String scope=competitionKey(competition);
        if(scope==null)return List.of();
        return tenant.read(actor,()->jdbc.query(evidenceSql()+" where s.competition_key=? and s.status in ('ready','partial') order by s.id,b.ordinal",
                KnowledgeService::mapEvidence,scope));
    }
    public List<String> unresolved(CurrentActor actor,String competition) {
        return tenant.read(actor,()->jdbc.query("select title,status from knowledge_source s where status in ('queued','processing','awaiting_visual','failed') and "+COMPETITION_FILTER,
                (r,n)->r.getString("title")+"："+r.getString("status"),competitionKey(competition)));
    }
    void replaceBlocks(UUID source,List<ParsedPart> parts) {
        jdbc.update("delete from knowledge_block where source_id=?",source);
        for(int i=0;i<parts.size();i++) {
            var part=parts.get(i);
            jdbc.update("insert into knowledge_block(id,source_id,ordinal,text,locator,parent_ordinal,search_text) values(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),source,i,part.text(),part.locator(),Math.max(0,i-1),TextProcessing.searchable(part.text()));
        }
    }
    void bump(CurrentActor actor){jdbc.update("update sc_athlete set knowledge_revision=knowledge_revision+1 where id=?",actor.athleteId());}
    private void enqueue(CurrentActor actor,UUID id){jdbc.update("insert into knowledge_job(id,source_id,athlete_id,actor_id) values(?,?,?,?)",UUID.randomUUID(),id,actor.athleteId(),actor.actorId());}
    private Source findRequest(CurrentActor actor,String request) {
        if(request==null)return null;
        var rows=jdbc.query("select * from knowledge_source where athlete_id=? and request_id=?",KnowledgeService::map,actor.athleteId(),request);
        if(rows.isEmpty())return null;
        if("deleted".equals(rows.get(0).status()))throw new ApiException(HttpStatus.CONFLICT,"SOURCE_DELETED","该请求对应的资料已被删除，请使用新请求上传。");
        return rows.get(0);
    }
    private Source getInside(UUID id) {
        var rows=jdbc.query("select * from knowledge_source where id=? and status<>'deleted'",KnowledgeService::map,id);
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    private static Source map(ResultSet r,int n)throws SQLException {
        String scope=competitionKey(r.getString("competition_key"));
        return new Source(r.getObject("id",UUID.class),r.getString("title"),sourceKind(r.getString("source_kind"),scope),r.getString("status"),r.getLong("revision"),
                r.getString("source_url"),scope,r.getTimestamp("created_at").toInstant(),r.getString("error_code"));
    }
    static Evidence mapEvidence(ResultSet r,int n)throws SQLException {
        return new Evidence(r.getObject("id",UUID.class),r.getObject("source_id",UUID.class),r.getString("title"),r.getString("text"),
                r.getString("locator"),r.getString("source_url"),r.getLong("revision"),sourceKind(r.getString("source_kind"),competitionKey(r.getString("competition_key"))));
    }
    static String evidenceSql(){return "select b.id,b.source_id,b.text,b.locator,s.title,s.source_url,s.revision,s.source_kind,s.competition_key from knowledge_block b join knowledge_source s on s.id=b.source_id";}
    private static String competitionKey(String value){return value==null||value.isBlank()?null:value;}
    private static String sourceKind(String kind,String competition){return competition==null&&"private_rule".equals(kind)?"user_document":kind;}
    private static String title(String value){return value==null||value.isBlank()?"未命名资料":value;}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SOURCE_NOT_FOUND","资料不存在、已被移除或不属于当前选手。");}
}
