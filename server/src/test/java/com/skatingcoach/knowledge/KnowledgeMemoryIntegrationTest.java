package com.skatingcoach.knowledge;

import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.gateway.LanguageModelGateway;
import com.skatingcoach.gateway.ModelReply;
import com.skatingcoach.memory.MemoryModels;
import com.skatingcoach.memory.MemoryService;
import com.skatingcoach.persistence.TenantJdbc;
import com.skatingcoach.planning.PlanService;
import com.skatingcoach.planning.PlanModels.Kind;
import com.skatingcoach.planning.PlanModels.PlanInput;
import com.skatingcoach.planning.PlanModels.RecoveryInput;
import com.skatingcoach.scoring.ScoringModels.Element;
import com.skatingcoach.scoring.ScoringModels.ElementType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"skating.knowledge.worker-enabled=false","KNOWLEDGE_STORAGE_PATH=./target/integration-storage"})
@Testcontainers
class KnowledgeMemoryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("skating_test").withUsername("postgres").withPassword("test-admin-only")
            .withInitScript("db/test-roles.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl);
        p.add("spring.datasource.username",()->"skating_test_app");
        p.add("spring.datasource.password",()->"integration-only-password");
        p.add("spring.flyway.url",DATABASE::getJdbcUrl);
        p.add("spring.flyway.user",DATABASE::getUsername);
        p.add("spring.flyway.password",DATABASE::getPassword);
    }
    @Autowired MemoryService memory; @Autowired KnowledgeService knowledge;
    @Autowired CoachContextService contexts; @Autowired TenantJdbc tenant;
    @Autowired PlanService plans; @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc; @Autowired DocumentParser parser; @Autowired SourceStorage storage;
    @Autowired com.skatingcoach.coach.RecommendationService recommendations;
    @Autowired com.skatingcoach.conversation.ConversationService conversations;
    @MockitoBean LanguageModelGateway model; @MockitoBean EmbeddingGateway embeddings;
    CurrentActor alice,bob;

    @BeforeEach void createAthletes() {
        reset(model,embeddings);
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        alice=new CurrentActor(UUID.randomUUID(),UUID.randomUUID());bob=new CurrentActor(UUID.randomUUID(),UUID.randomUUID());
        for(CurrentActor actor:List.of(alice,bob)) {
            admin.update("insert into sc_actor(id,display_name) values(?,'Integration fixture')",actor.actorId());
            admin.update("insert into sc_athlete(id,actor_id) values(?,?)",actor.athleteId(),actor.actorId());
        }
    }
    MemoryModels.MemoryItem note(CurrentActor actor,String text,String scope,String scopeId) {
        return memory.create(actor,new MemoryModels.Create(text,"note",null,scope,scopeId,UUID.randomUUID().toString()));
    }
    KnowledgeModels.Source document(CurrentActor actor,String text,String competition) {
        return knowledge.createText(actor,new KnowledgeModels.TextInput("Test source",text,null,competition,UUID.randomUUID().toString(),null));
    }

    @Test void savedRecoveryCanBeEditedWhenFrozenTimeHasTheSameValue() throws Exception {
        var main = plans.create(alice, new PlanInput("Main", Kind.MAIN, mapper.readTree("""
                {"elements":[{"id":"first","type":"COMBINATION","jumps":["3S","2T"],"plannedStartSeconds":30}]}
                """), null));
        var first = new Element("first", ElementType.SINGLE, List.of("2S"), new BigDecimal("30"));
        var recovery = plans.recovery(alice, main.id(), new RecoveryInput(main.revision(), "Recovery",
                List.of(first), List.of(first), null, null, "降周")).plan();
        ObjectNode edited = (ObjectNode) mapper.readTree("""
                {"completedPrefix":[{"id":"first","type":"SINGLE","jumps":["2S"],"plannedStartSeconds":30.0}],
                 "elements":[{"id":"first","type":"SINGLE","jumps":["2S"],"plannedStartSeconds":30.0},
                             {"id":"next","type":"SINGLE","jumps":["2A"],"plannedStartSeconds":60}]}
                """);
        edited.put("sourcePlanId", main.id().toString());
        edited.put("sourcePlanRevision", 999);
        var saved = plans.update(alice, recovery.id(), new PlanInput("Edited", Kind.RECOVERY, edited, recovery.revision()));
        assertThat(saved.body().path("sourcePlanRevision").asLong()).isEqualTo(main.revision());
        assertThat(saved.body().path("elements").size()).isEqualTo(2);
        ((ObjectNode) edited.path("completedPrefix").get(0)).put("plannedStartSeconds", 31);
        ((ObjectNode) edited.path("elements").get(0)).put("plannedStartSeconds", 31);
        assertThatThrownBy(() -> plans.update(alice, saved.id(), new PlanInput("Edited", Kind.RECOVERY, edited, saved.revision())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ACTUAL_PREFIX_LOCKED"));
    }

    @Test void rejectedCitationIsRepairedWithFeedbackAndNotPublishedAsEvidence() throws Exception {
        var payloads=new java.util.concurrent.CopyOnWriteArrayList<String>();
        when(model.available()).thenReturn(true);
        String valid="""
                {"explanation":"Training fixture","candidates":[{"title":"Plan","body":{"elements":[
                {"id":"e1","type":"SINGLE","jumps":["2A"],"plannedStartSeconds":20}]},"evidenceIds":[]}]}
                """;
        String rejected=valid.replace("\"evidenceIds\":[]","\"evidenceIds\":[\"fabricated-private-source\"]");
        when(model.generate(anyString(),anyString())).thenAnswer(call->{
            if(((String)call.getArgument(0)).startsWith("Extract only")) return new ModelReply("{\"items\":[]}","fixture",0,0);
            payloads.add(call.getArgument(1));
            return new ModelReply(payloads.size()==1?rejected:valid,"fixture",0,0);
        });
        var run=runRecommendation();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(payloads).hasSize(2);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(payloads.get(0)).has("rejectedAttempt")).isFalse();
        assertThat(json.readTree(payloads.get(1)).path("rejectedAttempt").asText()).isEqualTo(rejected);
        assertThat(json.readTree(payloads.get(1)).path("validationFeedback").asText()).contains("完整id");
        assertThat(run.result().path("validationAttempts").asInt()).isEqualTo(2);
        assertThat(run.result().path("repairReasons").get(0).asText()).isEqualTo("EVIDENCE_NOT_ALLOWED");
        assertThat(run.result().toString()).doesNotContain("fabricated-private-source","rejectedAttempt");
    }

    @Test void repeatedInvalidOutputStopsAfterOneRepairWithSafeReason() throws Exception {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(model.available()).thenReturn(true);
        when(model.generate(anyString(),anyString())).thenAnswer(call->{
            if(((String)call.getArgument(0)).startsWith("Extract only")) return new ModelReply("{\"items\":[]}","fixture",0,0);
            attempts.incrementAndGet();
            return new ModelReply("private-invalid-response","fixture",0,0);
        });
        var run=runRecommendation();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("INVALID_MODEL_OUTPUT_JSON_SYNTAX");
        assertThat(run.result()).isNull();
        assertThat(attempts.get()).isEqualTo(2);
    }

    private com.skatingcoach.coach.RecommendationModels.RunView runRecommendation() throws Exception {
        var session=conversations.create(alice);
        var run=recommendations.submit(alice,new com.skatingcoach.coach.RecommendationModels.RecommendationInput(
                UUID.randomUUID().toString(),session.id(),"Give me a training plan",null,null,null,null,null));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(Set.of("QUEUED","RUNNING").contains(run.status())&&System.nanoTime()<deadline) {
            Thread.sleep(25);
            run=recommendations.get(alice,run.id());
        }
        assertThat(run.status()).isNotIn("QUEUED","RUNNING");
        return run;
    }

    @Test void actualDatabaseRoleEnforcesIsolationAndPoolScopeDoesNotLeak() {
        var item=note(alice,"Alice private capability","global",null);
        assertThat(memory.snapshot(bob,null).items()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from memory_claim",Long.class)).isZero();
        assertThatThrownBy(()->memory.edit(bob,item.id(),new MemoryModels.Edit("stolen",1,null,null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->tenant.read(new CurrentActor(bob.actorId(),alice.athleteId()),()->"denied")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select rolsuper or rolbypassrls from pg_roles where rolname=current_user",Boolean.class)).isFalse();
    }
    @Test void correctionTakesEffectWithoutReembeddingAndRejectsStaleEdits() {
        var item=note(alice,"3Lz earlier description","global",null);
        var before=contexts.build(alice,"jump",null,null);
        var edited=memory.edit(alice,item.id(),new MemoryModels.Edit("Correction: 3Lo",1,null,null));
        assertThat(edited.revision()).isEqualTo(2);
        assertThat(contexts.isCurrent(alice,before)).isFalse();
        assertThat(contexts.build(alice,"jump",null,null).text()).contains("Correction: 3Lo").doesNotContain("3Lz earlier description");
        assertThatThrownBy(()->memory.edit(alice,item.id(),new MemoryModels.Edit("late write",1,null,null))).isInstanceOf(ApiException.class);
    }
    @Test void contextIncludesOnlyCurrentSessionAndCompetition() {
        note(alice,"long term preference","global",null);note(alice,"today tired","session","session-a");
        note(alice,"old event state","competition","event-old");note(alice,"this event goal","competition","event-new");
        String context=contexts.build(alice,"goal","session-a","event-new").text();
        assertThat(context).contains("long term preference","today tired","this event goal").doesNotContain("old event state");
        assertThat(contexts.build(alice,"goal",null,null).text()).doesNotContain("today tired","this event goal");
    }
    @Test void forgettingRemovesDerivedHistoryAndStopsOldSourceExtraction() {
        var item=note(alice,"private training fact","global",null);
        memory.edit(alice,item.id(),new MemoryModels.Edit("corrected private training fact",1,null,null));
        var prior=contexts.build(alice,"training",null,null);
        memory.forget(alice,item.id(),2);
        assertThat(contexts.isCurrent(alice,prior)).isFalse();
        assertThat(memory.snapshot(alice,null).items()).isEmpty();
        assertThat(memory.extractInput(alice,item.sourceId()).status()).isEqualTo("unchanged");
        tenant.read(alice,()-> {
            assertThat(jdbc.queryForObject("select count(*) from memory_claim_history where athlete_id=?",Long.class,alice.athleteId())).isZero();
            assertThat(jdbc.queryForObject("select content from memory_source where id=?",String.class,item.sourceId())).isNull();return null;
        });
    }
    @Test void missingProviderKeepsOriginalWithoutInventingMemories() {
        UUID source=UUID.randomUUID();memory.captureInput(alice,source,"I want to learn 3A","session-a");
        assertThat(memory.extractInput(alice,source).status()).isEqualTo("provider_unavailable");
        assertThat(memory.snapshot(alice,null).items()).isEmpty();verify(model,never()).generate(anyString(),anyString());
    }
    @Test void extractionIsIdempotentAndRequiresAnExactSourceQuote() {
        when(model.available()).thenReturn(true);
        when(model.generate(anyString(),anyString())).thenReturn(new ModelReply("""
            {"items":[{"statement":"Goal: learn 3A","quote":"want to learn 3A","kind":"goal","scopeType":"global","durable":true,"modality":"reported"},
            {"statement":"Mastered 4A","quote":"not present","kind":"capability","modality":"reported"}]}
            ""","fixture",1L,1L));
        UUID source=UUID.randomUUID();memory.captureInput(alice,source,"I want to learn 3A","s1");
        assertThat(memory.extractInput(alice,source).saved()).isEqualTo(1);
        assertThat(memory.extractInput(alice,source).saved()).isZero();
        assertThat(memory.snapshot(alice,null).items()).singleElement().satisfies(m->assertThat(m.kind()).isEqualTo("goal"));
    }
    @Test void privateChineseKnowledgeIsSearchableButNeverCrossesAthletes() {
        document(alice,"3S+3T 后半程疲劳，保留脚注和完整表格。",null);
        var result=knowledge.search(alice,"后半程疲劳",null,10);
        assertThat(result.mode()).isEqualTo("lexical");assertThat(result.items()).isNotEmpty();
        assertThat(knowledge.search(bob,"后半程疲劳",null,10).items()).isEmpty();
        assertThat(result.items().get(0).locator()).contains("text;part=");
    }
    @Test void blankCompetitionDocumentsRemainGlobalAcrossSearchAndCoachContext() {
        var source=document(alice,"稳定落冰训练反馈", " \t ");
        assertThat(source.competitionKey()).isNull();
        assertThat(source.sourceKind()).isEqualTo("user_document");
        document(alice,"另一个赛事的稳定落冰条款", "event-other");
        for(String scope:Arrays.asList(null,""," \t ","event-current")) {
            assertThat(knowledge.search(alice,"稳定落冰",scope,10).items()).extracting(Evidence::sourceId).containsExactly(source.id());
        }
        assertThat(contexts.build(alice,"稳定落冰",null,null).text()).contains("稳定落冰训练反馈").doesNotContain("另一个赛事");
        assertThat(knowledge.search(bob,"稳定落冰",null,10).items()).isEmpty();
        // Compatibility with rows written before blank scopes were normalized.
        tenant.write(alice,()->{jdbc.update("update knowledge_source set competition_key='',source_kind='private_rule' where id=?",source.id());return null;});
        assertThat(knowledge.search(alice,"稳定落冰",null,10).items()).singleElement().satisfies(e->assertThat(e.sourceKind()).isEqualTo("user_document"));
        assertThat(knowledge.detail(alice,source.id()).source().competitionKey()).isNull();
    }
    @Test void blankUploadScopeIsGlobalAndItsPendingStatusReachesTheCoach() throws Exception {
        var source=knowledge.upload(alice,"blank scope upload","text/plain","  ",UUID.randomUUID().toString(),new ByteArrayInputStream("training evidence".getBytes(StandardCharsets.UTF_8)));
        assertThat(source.competitionKey()).isNull();
        assertThat(source.sourceKind()).isEqualTo("user_document");
        assertThat(knowledge.unresolved(alice,null)).contains("blank scope upload：queued");
        assertThat(knowledge.unresolved(bob,null)).isEmpty();
    }
    @Test void explicitNaturalCorrectionSupersedesAndForgettingTraversesItsSources() {
        var old=memory.create(alice,new MemoryModels.Create("3Lz was unstable","capability","jump:3Lz","global",null,"old"));
        when(model.available()).thenReturn(true);
        when(model.generate(anyString(),anyString())).thenReturn(new ModelReply("""
            {"items":[{"statement":"3Lz is stable now","quote":"3Lz is stable now","kind":"capability","subjectKey":"jump:3Lz",
            "scopeType":"global","durable":true,"modality":"reported","relation":"corrects","relatedId":"%s"}]}
            """.formatted(old.id()),"fixture",1L,1L));
        UUID input=UUID.randomUUID();memory.captureInput(alice,input,"Correction: 3Lz is stable now","s1");
        memory.extractInput(alice,input);
        var current=memory.snapshot(alice,null).items();
        assertThat(current).hasSize(1);assertThat(current.get(0).statement()).isEqualTo("3Lz is stable now");
        memory.forget(alice,current.get(0).id(),1);
        assertThat(memory.snapshot(alice,null).items()).isEmpty();
        tenant.read(alice,()-> {
            assertThat(jdbc.queryForObject("select content from memory_source where id=?",String.class,old.sourceId())).isNull();
            assertThat(jdbc.queryForObject("select content from memory_source where id=?",String.class,input)).isNull();return null;
        });
    }
    @Test void lateExtractionCannotOverrideAConcurrentUserCorrection() {
        UUID input=UUID.randomUUID();memory.captureInput(alice,input,"I prefer 3Lz","s1");
        when(model.available()).thenReturn(true);
        when(model.generate(anyString(),anyString())).thenAnswer(call->{
            note(alice,"Current preference is 3Lo","global",null);
            return new ModelReply("""
                {"items":[{"statement":"Prefers 3Lz","quote":"prefer 3Lz","kind":"preference","subjectKey":"preferred-jump",
                "scopeType":"global","durable":true,"modality":"reported"}]}
                ""","fixture",1L,1L);
        });
        memory.extractInput(alice,input);
        assertThat(memory.snapshot(alice,null).items()).filteredOn(i->i.statement().equals("Prefers 3Lz"))
                .singleElement().satisfies(i->assertThat(i.status()).isEqualTo("candidate"));
    }
    @Test void wrongCompetitionNeverEntersMandatoryOrSemanticContext() {
        document(alice,"3Lz special rule event A","event-a");document(alice,"3Lz different rule event B","event-b");
        assertThat(contexts.build(alice,"3Lz",null,"event-a").text()).contains("special rule event A").doesNotContain("different rule event B");
    }
    @Test void sourceVersionReplacementAndDeletionDoNotResurrectOldRules() {
        var first=document(alice,"obsolete 3Lz rule","event-a");
        var second=knowledge.createText(alice,new KnowledgeModels.TextInput("Revised rule","current 3Lz rule",null,"event-a",UUID.randomUUID().toString(),first.id()));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(knowledge.search(alice,"3Lz","event-a",10).items()).allSatisfy(e->assertThat(e.text()).doesNotContain("obsolete"));
        assertThat(knowledge.delete(alice,first.id()).get("status")).isEqualTo("deleted");
        var before=contexts.build(alice,"3Lz",null,"event-a");knowledge.delete(alice,second.id());
        assertThat(contexts.isCurrent(alice,before)).isFalse();assertThat(knowledge.search(alice,"3Lz","event-a",10).items()).isEmpty();
    }
    @Test void actualPgvectorQueryCombinesLexicalAndSameModelVectors() {
        var source=document(alice,"different vocabulary",null);
        tenant.write(alice,()-> {jdbc.update("update knowledge_block set embedding='[1,0,0]'::vector,embedding_model='fixture-space',embedding_dimensions=3 where source_id=?",source.id());return null;});
        when(embeddings.available()).thenReturn(true);when(embeddings.model()).thenReturn("fixture-space");
        when(embeddings.embed(anyList())).thenReturn(List.of(new double[]{1,0,0}));
        var result=knowledge.search(alice,"semantic paraphrase",null,10);
        assertThat(result.mode()).isEqualTo("hybrid");assertThat(result.items()).hasSize(1);
    }
    @Test void mislabeledVideoWaitsForVisualAnalysisAndCannotEnterRetrieval()throws Exception {
        var source=knowledge.upload(alice,"mislabeled training.txt","text/plain",null,UUID.randomUUID().toString(),
                new ByteArrayInputStream(DocumentParserTest.syntheticVideo()));
        jdbc.update("update knowledge_job set status='done' where source_id<>?",source.id());
        new KnowledgeWorker(jdbc,tenant,storage,parser,knowledge,embeddings).tick();
        var detail=knowledge.detail(alice,source.id());
        assertThat(detail.source().status()).isEqualTo("awaiting_visual");
        assertThat(detail.source().errorCode()).isEqualTo("VISUAL_PROVIDER_REQUIRED");
        assertThat(detail.blocks()).isEmpty();
        assertThat(knowledge.search(alice,"Synthetic MCP upload test",null,10).items()).isEmpty();
        assertThat(contexts.build(alice,"training",null,null).text()).contains("awaiting_visual");
        verify(embeddings,never()).embed(anyList());
        knowledge.delete(alice,source.id());
    }
    @Test void uploadIsDurableAndWorkerParsesActualBytes()throws Exception {
        var source=knowledge.upload(alice,"training.txt","text/plain",null,UUID.randomUUID().toString(),
                new ByteArrayInputStream("3S+3T training context".getBytes(StandardCharsets.UTF_8)));
        // Isolate the dispatcher fixture from other tests' text-index jobs.
        jdbc.update("update knowledge_job set status='done' where source_id<>?",source.id());
        new KnowledgeWorker(jdbc,tenant,storage,parser,knowledge,embeddings).tick();
        var detail=knowledge.detail(alice,source.id());
        assertThat(detail.source().status()).isEqualTo("partial");assertThat(detail.blocks()).isNotEmpty();
        assertThat(detail.blocks().get(0).text()).contains("3S+3T");
        knowledge.delete(alice,source.id());
    }
}
