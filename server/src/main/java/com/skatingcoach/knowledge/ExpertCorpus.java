package com.skatingcoach.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import com.skatingcoach.scoring.ScoringModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Versioned, public editorial evidence compiled from the fingerprint-verified rule sources. */
@Component
public class ExpertCorpus {
    public static final String SCOPE="isu-2026-27-senior-women-fs";
    public static final String LABEL="2026/27 ISU 成年女子单人自由滑";
    record Source(String title,String url,String sha256,String revision) {}
    record Locator(String source,List<Integer> pages,String section) {}
    record Fact(String key,JsonNode value,List<String> refs) {}
    record Entry(String key,String topic,JsonNode claim,String source,int page,String section,String summary,String conditions,
                 List<String> factKeys,List<String> lookup,String anchorSha256) {}
    record Pack(Map<String,String> scope,String bundleSha256,Map<String,Source> sources,Map<String,Locator> locators,List<Fact> facts,List<Entry> entries) {}
    record Topics(Map<String,List<String>> coverage,Map<String,String> patterns,List<List<String>> aliases,Set<String> policyTopics) {}
    record Teaching(String key,String title,String url,String section,List<String> patterns,String summary,String conditions) {}
    record Passage(String key,String topic,String source,JsonNode claim,String summary,String conditions,List<String> lookups,Evidence evidence) {}
    final Pack pack;
    final Topics topics;
    final Map<String,Pattern> patterns=new LinkedHashMap<>();
    final Map<String,List<Pattern>> coverage=new LinkedHashMap<>();
    final Map<Pattern,String> aliases=new LinkedHashMap<>();
    final List<Passage> rules=new ArrayList<>(), coaching=new ArrayList<>();
    final Map<String,Passage> byKey=new LinkedHashMap<>();
    final Map<UUID,Evidence> byId=new HashMap<>();

    public ExpertCorpus(ObjectMapper mapper) throws IOException {
        pack=read(mapper,"isu-2026-27-women-fs.json",new TypeReference<>(){});
        topics=read(mapper,"rule-topics.json",new TypeReference<>(){});
        topics.patterns.forEach((k,v)->patterns.put(k,pattern(v)));
        topics.coverage.forEach((k,v)->coverage.put(k,v.stream().map(ExpertCorpus::pattern).toList()));
        topics.aliases.forEach(a->aliases.put(pattern(a.get(0)),a.get(1)));
        for (var row:pack.entries) {
            var lookups=new LinkedHashSet<>(row.lookup);
            lookups.add(row.key);
            var passage=new Passage(row.key,row.topic,row.source,row.claim,row.summary,row.conditions,List.copyOf(lookups),
                    ruleEvidence(row.key,row.source,row.page,row.section,row.summary,row.conditions));
            add(passage,rules);
        }
        for (var fact:pack.facts) {
            String code=fact.key.startsWith("bv.")?fact.key.substring(3):null;
            var loc=pack.locators.get(fact.refs.get(0));
            String summary=code==null?fact.key+" 的已核规则值为 "+fact.value.asText(fact.value.toString())+"。":
                    code+" 的基础分（BASE）为 "+fact.value.asText()+"。";
            String conditions=code==null?LABEL+"，适用细节需结合所列条款。":"分值表的单个动作行。整组计值还取决于连接、重复、技术标记和执行时点。";
            add(new Passage(fact.key,fact.key,loc.source,null,summary,conditions,code==null?List.of(fact.key):List.of(fact.key,code),
                    ruleEvidence(fact.key,loc.source,loc.pages.get(0),loc.section,summary,conditions)),null);
        }
        List<Teaching> research=read(mapper,"coaching-research.json",new TypeReference<>(){});
        for (var row:research) {
            coverage.put(row.key,row.patterns.stream().map(ExpertCorpus::pattern).toList());
            var evidence=new Evidence(id(row.key),id(row.url),row.title,row.summary+"\n适用条件："+row.conditions,row.section,row.url,1,"coaching_research");
            add(new Passage(row.key,row.key,"research",null,row.summary,row.conditions,List.of(row.key),evidence),coaching);
        }
    }
    private Evidence ruleEvidence(String key,String source,int page,String section,String summary,String conditions) {
        var doc=pack.sources.get(source);
        return new Evidence(id(revision()+"/"+key),id(doc.sha256),doc.title,summary+"\n适用条件："+conditions,
                "PDF p."+page+" · "+section,doc.url+"#page="+page,1,"official_rule");
    }
    private void add(Passage item,List<Passage> group) {
        if (byKey.put(item.key.toLowerCase(Locale.ROOT),item)!=null) throw new IllegalStateException("Duplicate expert corpus key "+item.key);
        byId.put(item.evidence.id(),item.evidence);
        if (group!=null) group.add(item);
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    static Pattern pattern(String value) { return Pattern.compile(value,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE); }
    public String revision() { return pack.scope.get("revision"); }
    public Map<String,Object> manifest() {
        return Map.of("competitionKey",SCOPE,"label",LABEL,"revision",revision(),"fingerprint",pack.bundleSha256,
                "sources",pack.sources,"factCount",pack.facts.size(),"passageCount",rules.size(),"researchCount",coaching.size(),
                "effectiveFrom",ExpertRetrieval.FROM,"effectiveThrough",ExpertRetrieval.UNTIL);
    }
    public boolean current(String scope) {
        LocalDate now=LocalDate.now(ZoneOffset.UTC);
        return applicableScope(scope) && !now.isBefore(ExpertRetrieval.FROM) && !now.isAfter(ExpertRetrieval.UNTIL);
    }
    /** Only rules with an exact representation in the general plan calculator belong here. */
    public RuleSet referenceRules(String scope) {
        if (!current(scope)) return null;
        Map<String,BigDecimal> values=new LinkedHashMap<>();
        Map<String,JsonNode> facts=new HashMap<>();
        for(var fact:pack.facts) {
            facts.put(fact.key,fact.value);
            if(fact.key.startsWith("bv.")) values.put(fact.key.substring(3),new BigDecimal(fact.value.asText()));
        }
        var limits=new Limits(CountLimit.atMost(facts.get("element_limit").intValue()),CountLimit.atMost(facts.get("combo_limit").intValue()),
                CountLimit.atMost(facts.get("sequence_limit").intValue()),CountLimit.atMost(facts.get("combo_max_length").intValue()),Map.of(),false,null);
        return new RuleSet(SCOPE,revision(),"ISU R1、R2、R3、R6 · "+pack.bundleSha256,Map.copyOf(values),
                null,null,limits,facts.get("bv_places").intValue(),RoundingMode.HALF_UP,false,
                List.of("比赛时长、执行时点、Axel、实际技术判定、重复与组合例外须按对应条款另行核对。"));
    }
    boolean matches(String name,String text) { return patterns.get(name).matcher(text).find(); }
    boolean covers(String topic,String text) {
        var requirements=coverage.get(topic);
        return requirements!=null && requirements.stream().allMatch(p->p.matcher(text).find());
    }
    public boolean applicableScope(String scope) {
        return scope!=null && Set.of(SCOPE,LABEL,revision()).contains(scope.strip());
    }
    private static <T> T read(ObjectMapper mapper,String file,TypeReference<T> type) throws IOException {
        try(var in=new ClassPathResource("expert/"+file).getInputStream()) { return mapper.readValue(in,type); }
    }
}
