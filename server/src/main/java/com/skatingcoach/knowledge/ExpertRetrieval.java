package com.skatingcoach.knowledge;

import com.skatingcoach.knowledge.ExpertCorpus.Passage;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ExpertRetrieval {
    static final LocalDate FROM = LocalDate.of(2026,11,1), UNTIL = LocalDate.of(2027,6,30);
    private final ExpertCorpus corpus;
    private final LocalBge encoder;
    private final Map<String,double[]> publicVectors = new ConcurrentHashMap<>();
    public record Citation(String source,String url,String pdfSha256,String anchorSha256,String section,List<Integer> pages) {}
    public record Answer(String key, UUID evidenceId, String text, String conditions,List<String> factKeys,List<Citation> citations) {}
    public record Result(String status, String reason, String method, String scope, String revision,
                         String fingerprint, List<Answer> answers, List<Evidence> evidence) {}

    public ExpertRetrieval(ExpertCorpus corpus, LocalBge encoder) {
        this.corpus=corpus;
        this.encoder=encoder;
    }
    private static final Pattern SEASON=Pattern.compile("(?<!\\d)(20\\d\\d)\\s*[/–—-]\\s*(?:20)?(\\d\\d)(?!\\d)");
    private static final Pattern NAMED_JUMP=Pattern.compile("([一两二三四五1-5])(?:周|圈)(?:的)?(后外点冰|后内结环|后外结环|后内点冰|勾手|阿克塞尔)");
    private static final Map<String,String> NAMES=Map.of("后外点冰","t","后内结环","s","后外结环","lo","后内点冰","f","勾手","lz","阿克塞尔","a");
    private static String codesForNames(String text) {
        return NAMED_JUMP.matcher(text).replaceAll(m->{
            char c=m.group(1).charAt(0);
            int rotations="一二三四五".indexOf(c)+1;
            if(rotations==0) rotations=c=='两'?2:Character.digit(c,10);
            return " "+rotations+NAMES.get(m.group(2))+" ";
        });
    }
    public Result ask(String question, String scope, String revision, List<String> sources) {
        return search(question,scope,revision,sources,LocalDate.now(ZoneOffset.UTC),true);
    }
    Result search(String question,String scope,String revision,List<String> sources,LocalDate date,boolean semantic) {
        String text = Normalizer.normalize(Objects.toString(question,""),Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (text.isBlank() || text.length()>1200 || sources!=null && sources.size()>8)
            throw new IllegalArgumentException("问题须为 1–1200 字，最多选择八个来源。");
        Set<String> filters = new HashSet<>();
        if (sources!=null) sources.forEach(s->filters.add(s.toUpperCase(Locale.ROOT)));
        if (revision!=null && !revision.equals(corpus.revision())) {
            boolean former = List.of(corpus.revision().replace("-5s",""),corpus.revision().replace("_m2-5s","")).contains(revision);
            return empty(former?"non_current":"no_match",former?"revision_outside_current_period":"unknown_revision",scope);
        }
        // Technique research carries its own population/measurement labels. A scoring question always uses rule scope.
        var teaching=corpus.coaching.stream().filter(p->corpus.covers(p.topic(),text) || p.key().equals(text)).toList();
        boolean scoringIntent=has("QUINT_SCORE",text) || text.matches(".*(?:规则|算分|判罚|技术组|基础分|标记|计数|规定|几分|怎样标).*");
        boolean formalTopic=corpus.rules.stream().anyMatch(p->corpus.covers(p.topic(),text));
        if (!scoringIntent && !formalTopic && !teaching.isEmpty() && (filters.isEmpty() || filters.contains("RESEARCH"))) {
            return rank(text,"coaching_research",teaching,semantic);
        }
        boolean otherSeason=SEASON.matcher(text).results().anyMatch(m->!m.group(1).equals("2026") || !m.group(2).equals("27"));
        if (!corpus.applicableScope(scope) || otherSeason || has("SHORT_PROGRAM",text) || has("PAIRS",text) || has("OTHER_EVENTS",text))
            return empty("no_match","unsupported_scope",scope);
        if (date.isBefore(FROM) || date.isAfter(UNTIL))
            return empty("non_current","revision_outside_current_period",scope);
        if (has("OUTSIDE_INDEX",text) || has("LANDING_FAILURE",text) && leavesGroup(text)) return none(scope);
        var available=corpus.rules.stream().filter(p->(filters.isEmpty() || filters.contains(p.source())) && applicable(p,text)).toList();
        var locators=matches("LOCATORS",text).stream().map(m->m.group().replaceFirst("\\.+$","")).toList();
        var base=corpus.byKey.get(text);
        if (base!=null && !base.source().equals("research") && locators.isEmpty() && !base.key().startsWith("bv.")
                && (filters.isEmpty() || filters.contains(base.source()))) return finish(scope,"structured",List.of(base));
        var candidates=locators.isEmpty()?available:available.stream().filter(p->p.lookups().stream().anyMatch(locators::contains)).toList();
        String rest=codesForNames(corpus.patterns.get("LOCATORS").matcher(text).replaceAll(" "));
        var codes=matches("CODES",rest);
        var covered=candidates.stream().filter(p->corpus.covers(p.topic(),rest)).toList();
        boolean policy=covered.stream().anyMatch(p->corpus.topics.policyTopics().contains(p.topic()))
                || has("ROTATION_DISTINCTION",rest) || corpus.covers("R2.repetition.high_quota",rest) && !has("SAME_NAME",rest);
        boolean five=has("QUINT",rest) || codes.stream().anyMatch(m->m.group().startsWith("5"));
        if (five && !policy) {
            boolean count=has("SAME_NAME",rest) && has("COUNT",rest);
            if (count && !(rotation("TRIPLE",'3',rest,codes) && rotation("QUAD",'4',rest,codes))) return none(scope);
            boolean linked=false;
            for (int i=1;i<codes.size();i++) {
                var a=codes.get(i-1); var b=codes.get(i);
                if ((a.group().startsWith("5") || b.group().startsWith("5"))
                        && corpus.patterns.get("CODE_CONNECTION").matcher(rest.substring(a.end(),b.start())).matches()) linked=true;
            }
            String groupText=corpus.patterns.get("NEGATED_GROUP").matcher(rest).replaceAll("");
            if (linked || has("GROUP_CALL",rest) || has("GROUP",groupText)) return topic(scope,candidates,"quint_group");
            if (count) return topic(scope,candidates,"quint_same_name_count");
            if (has("REPETITION",rest)) return none(scope);
            if (!has("QUINT_SCORE",rest) && !(codes.size()==1 && codes.get(0).group().equals(rest.strip())))
                return topic(scope,candidates,"quint_solo");
        }
        if (!locators.isEmpty()) {
            if (!candidates.isEmpty()) return finish(scope,"structured",candidates);
            return exactFacts(scope,locators,filters);
        }
        if (!policy && !codes.isEmpty()) {
            boolean literal=codes.size()==1 && codes.get(0).group().equals(rest.strip());
            if(!literal && !has("QUINT_SCORE",rest) && !rest.matches(".*(?:\\bbv\\b|值几分|几分|表内分|多少[点點]).*")) return none(scope);
            var keys=new LinkedHashSet<String>();
            for (var code:codes) {
                var mark=corpus.patterns.get("SEPARATE_MARK").matcher(rest.substring(code.end()));
                keys.add("bv."+code.group()+(mark.lookingAt()?mark.group(1):""));
            }
            if (keys.size()>1 && (has("GROUP",rest) || rest.contains("+"))) return none(scope);
            return exactFacts(scope,keys,filters);
        }
        if (five && !policy) return none(scope);
        if (!covered.isEmpty()) return rank(text,scope,covered,semantic);
        var aliases=new HashSet<String>(Arrays.asList(text.split("[^a-z0-9_]+")));
        corpus.aliases.forEach((p,key)->{if(p.matcher(text).find()) aliases.add(key);});
        var hits=candidates.stream().filter(p->p.lookups().stream().anyMatch(aliases::contains)).toList();
        return finish(scope,"structured",hits);
    }
    private boolean has(String pattern,String text) { return corpus.matches(pattern,text); }
    private List<MatchResult> matches(String name,String text) { return corpus.patterns.get(name).matcher(text).results().toList(); }
    private boolean rotation(String name,char number,String text,List<MatchResult> codes) {
        return has(name,text) || codes.stream().anyMatch(m->m.group().charAt(0)==number);
    }
    private boolean leavesGroup(String text) {
        String positive=corpus.patterns.get("NEGATED_SEPARATE_ELEMENT").matcher(text).replaceAll("");
        return has("SEPARATE_ELEMENT",positive) || has("GROUP_ENDED",positive);
    }
    private boolean applicable(Passage p,String text) {
        return switch(p.key()) {
            case "R2.markings.quarter" -> !has("NOT_EXACT_QUARTER",text);
            case "R2.markings.rotation_call","R2.markings.downgrade_value" -> !text.matches(".*(?:欧拉|歐拉|半环|半環|\\beuler\\b|\\beu\\b).*");
            case "R2.combinations.euler" -> !corpus.covers("R2.group_exceptions.euler_sequence",text);
            case "R2.interrupted.same_group" -> !leavesGroup(text) && (p.lookups().contains(text)
                    || has("GROUP",corpus.patterns.get("NEGATED_GROUP").matcher(text).replaceAll("")));
            default -> true;
        };
    }
    private Result exactFacts(String scope,Collection<String> keys,Set<String> sources) {
        List<Passage> found=new ArrayList<>();
        for (var key:keys) {
            var p=key.startsWith("bv.")?corpus.byKey.getOrDefault("r3.base."+key.substring(3),corpus.byKey.get(key)):corpus.byKey.get(key);
            if (p==null || !sources.isEmpty() && !sources.contains(p.source())) return none(scope);
            found.add(p);
        }
        return finish(scope,"structured",found);
    }
    private Result topic(String scope,List<Passage> rows,String name) {
        return finish(scope,"structured",rows.stream().filter(p->p.topic().equals(name)).toList());
    }
    private Result rank(String query,String scope,List<Passage> covered,boolean semantic) {
        var topics=new LinkedHashSet<String>();
        covered.forEach(p->topics.add(p.topic()));
        if (semantic && encoder.configured()) {
            try {
                var vector=encoder.encode(query,true);
                Map<String,Double> scores=new LinkedHashMap<>();
                for (var p:covered) {
                    var doc=publicVectors.computeIfAbsent(p.key(),k->encoder.encode(p.conditions()+"\n"+p.summary(),false));
                    double score=0;
                    for (int j=0;j<vector.length;j++) score+=vector[j]*doc[j];
                    scores.merge(p.topic(),score,Math::max);
                }
                var order=scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).toList();
                var best=order.get(0);
                if (best.getValue()<0.52 || order.size()>1 && best.getValue()-order.get(1).getValue()<0.04)
                    return empty("no_match","insufficient_topic_margin",scope);
                return finish(scope,"semantic_local",covered.stream().filter(p->p.topic().equals(best.getKey())).toList());
            } catch(IllegalStateException error) {
                if (topics.size()==1) return finish(scope,"reviewed_topic_fallback",covered);
                return empty("no_match","encoder_unavailable",scope);
            }
        }
        return topics.size()==1?finish(scope,"reviewed_topic",covered):empty("no_match","ambiguous_topics",scope);
    }
    private Result finish(String scope,String method,List<Passage> hits) {
        if (hits.isEmpty()) return none(scope);
        var claims=new HashMap<String,Set<String>>();
        for (var row:hits) if(row.claim()!=null && !row.claim().isNull())
            claims.computeIfAbsent(row.topic(),k->new HashSet<>()).add(row.claim().toString());
        boolean conflict=claims.values().stream().anyMatch(values->values.size()>1);
        var answers=conflict?List.<Answer>of():hits.stream().map(this::answer).toList();
        var evidence=hits.stream().map(Passage::evidence).map(e->conflict?new Evidence(e.id(),e.sourceId(),e.title(),"",e.locator(),e.sourceUrl(),e.sourceVersion(),e.sourceKind()):e).toList();
        return result(conflict?"conflict":"matched",conflict?"same_scope_claim_conflict":null,method,scope,answers,evidence);
    }
    private Answer answer(Passage p) {
        var keys=new ArrayList<String>();
        var citations=new ArrayList<Citation>();
        corpus.pack.entries().stream().filter(e->e.key().equals(p.key())).findFirst().ifPresent(entry->{
            keys.addAll(entry.factKeys());
            var src=corpus.pack.sources().get(entry.source());
            citations.add(new Citation(entry.source(),src.url()+"#page="+entry.page(),src.sha256(),entry.anchorSha256(),entry.section(),List.of(entry.page())));
        });
        if(keys.isEmpty() && corpus.pack.facts().stream().anyMatch(f->f.key().equals(p.key()))) keys.add(p.key());
        for(var fact:corpus.pack.facts()) if(keys.contains(fact.key())) for(var ref:fact.refs()) {
            var loc=corpus.pack.locators().get(ref);
            var src=corpus.pack.sources().get(loc.source());
            if(citations.stream().noneMatch(c->c.source().equals(loc.source()) && c.section().equals(loc.section())))
                citations.add(new Citation(loc.source(),src.url()+"#page="+loc.pages().get(0),src.sha256(),null,loc.section(),loc.pages()));
        }
        if(p.source().equals("research")) citations.add(new Citation("research",p.evidence().sourceUrl(),null,null,p.evidence().locator(),List.of()));
        return new Answer(p.key(),p.evidence().id(),p.summary(),p.conditions(),List.copyOf(keys),List.copyOf(citations));
    }
    private Result none(String scope) { return empty("no_match","no_reviewed_match",scope); }
    private Result empty(String status,String reason,String scope) { return result(status,reason,"structured",scope,List.of(),List.of()); }
    private Result result(String status,String reason,String method,String scope,List<Answer> answers,List<Evidence> evidence) {
        boolean research="coaching_research".equals(scope);
        return new Result(status,reason,method,research?scope:corpus.applicableScope(scope)?ExpertCorpus.SCOPE:scope,
                research?"coaching-research-1":corpus.revision(),research?null:corpus.pack.bundleSha256(),answers,evidence);
    }
}
