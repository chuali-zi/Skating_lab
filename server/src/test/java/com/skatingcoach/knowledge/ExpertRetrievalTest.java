package com.skatingcoach.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

class ExpertRetrievalTest {
    private static final String SCOPE=ExpertCorpus.SCOPE;
    private static final LocalDate SNAPSHOT=LocalDate.of(2026,11,20);
    private final ExpertCorpus corpus=new ExpertCorpus(new ObjectMapper());
    ExpertRetrievalTest() throws Exception {}
    record Case(String query,String key,String page) {}
    static final List<Case> CASES=List.of(
        new Case("第七个跳跃元素还有基础分吗？","R1.612.senior_women.extra","p.70"),
        new Case("普通连跳的落冰脚与后一跳的起跳脚如何连接？","R2.combinations.connection","p.28"),
        new Case("做了两组三跳连跳，第二组的第三跳还计值吗？","R2.repeat_outcome.second_long_group","p.32"),
        new Case("三周和四周重复的名额有几个？","R2.repetition.high_quota","p.27"),
        new Case("欧拉连跳和连续跳在同一套节目里一起用，如何计值？","R2.group_exceptions.euler_sequence","p.33"),
        new Case("连跳漏接没有尝试，与已报无分的跳有什么差别？","R2.markings.attempted_jump","p.31"),
        new Case("原计划 3T 却跳成 2T，跳空后怎样更新重复次数？","R2.markings.popped","p.30"),
        new Case("落冰欠周和降组的基础分分别怎么判？","R2.markings.rotation_call","p.29"),
        new Case("一跳落冰刚好少转四分之一圈，该标什么符号？","R2.markings.quarter","p.29"),
        new Case("连跳里步出后紧接着跳，后续跳还算分吗？","R2.interrupted.same_group","p.33"),
        new Case("自由滑第三组连跳的基础分怎么计算？","R2.third_combo","p.33"),
        new Case("自由滑节目过半以后起跳，哪些跳的基值享有额外倍率？","R1.353","p.9"),
        new Case("成年女子自由滑里能放几套连跳，每套可以包含几跳？","R1.612.remarks.group_limits","p.71"),
        new Case("5S +SEQ+ 2A 允许吗？","R1.612.remarks.quint_group","p.71"),
        new Case("做独立的五周单跳（不放进连跳）行吗？","R2.quint.quint_solo","p.27"),
        new Case("请问 3T＜ 的基础分是多少？","R3.base.3T<","p.3"),
        new Case("5S 带 << 的 BASE 分是多少？","R3.base.5S<<","p.5"),
        new Case("2A 的基础分？","bv.2A","p.3"),
        new Case("双周跳起跳的自由腿和手臂对角动量有何影响？","takeoff-six-doubles","图 3"),
        new Case("青少年学习 Axel 周数进阶的研究有什么限制？","youth-axel","2.1"),
        new Case("用视频观察落冰姿态，证据有什么限制？","landing-observation","方法"),
        new Case("一套训练后半程疲劳反复跳跃的研究能说明什么？","fatigue-program",""),
        new Case("长期发展框架如何区分技能发展阶段？","development-stage","")
    );
    @Test void groundedQueriesAndAbstentions() throws Exception {
        try(var encoder=new LocalBge("")) { evaluate(encoder,false); }
    }
    @Test @EnabledIfEnvironmentVariable(named="SKATING_BGE_DIR",matches=".+")
    void actualPinnedCpuEmbeddingRanksApplicableEvidence() throws Exception {
        try(var encoder=new LocalBge(System.getenv("SKATING_BGE_DIR"))) {
            assertThat(encoder.encode("落冰复盘",true)).hasSize(512);
            evaluate(encoder,true);
        }
    }
    private void evaluate(LocalBge encoder,boolean semantic) {
        var expert=new ExpertRetrieval(corpus,encoder);
        double reciprocalRanks=0;
        int semanticHits=0;
        var failures=new ArrayList<String>();
        for(var c:CASES) {
            var r=expert.search(c.query,SCOPE,null,List.of(),SNAPSHOT,semantic);
            int position=0;
            for(int i=0;i<Math.min(r.answers().size(),3);i++) {
                var answer=r.answers().get(i);
                if(answer.key().equals(c.key) && r.evidence().stream().anyMatch(e->e.id().equals(answer.evidenceId())
                        && e.locator().contains(c.page) && e.sourceUrl().startsWith("https://"))) position=i+1;
            }
            if(position==0) failures.add(c.key+" "+r.status()+"/"+r.reason()+" "+r.answers().stream().map(ExpertRetrieval.Answer::key).toList());
            else reciprocalRanks+=1d/position;
            if(r.method().equals("semantic_local")) semanticHits++;
        }
        System.out.printf(Locale.ROOT,"Expert benchmark semantic=%b cited@3=%d/%d MRR@3=%.3f encoder-routed=%d%n",
                semantic,CASES.size()-failures.size(),CASES.size(),reciprocalRanks/CASES.size(),semanticHits);
        if(semantic) assertThat(failures).isEmpty();
        else assertThat(failures).containsExactly("youth-axel no_match/ambiguous_topics []");
        if(semantic) assertThat(semanticHits).isGreaterThan(8);
        var negative=List.of("少年组自由滑的 3T< 多少分？","男单 2A 的基础分？","双人抛跳 5S 的基础分？",
                "成年女单短节目里 3Lz 的分数？","5T< 的基础分？","音乐时长和节目内容分如何计算？",
                "连跳已经结束，摔倒后下一个独立跳怎么计值？","今天的商场在哪里？");
        for(String q:negative) {
            var r=expert.search(q,SCOPE,null,List.of(),SNAPSHOT,semantic);
            assertThat(r.status()).as(q).isEqualTo("no_match");assertThat(r.answers()).isEmpty();
        }
        String conflict="同名三周、四周和五周，算几个不同跳跃？";
        var result=expert.search(conflict,SCOPE,null,List.of(),SNAPSHOT,semantic);
        assertThat(result.status()).isEqualTo("conflict");assertThat(result.answers()).isEmpty();
        assertThat(result.evidence()).hasSize(3).allMatch(e->e.text().isEmpty() && e.sourceUrl().contains("#page="));
        assertThat(expert.search(conflict,SCOPE,null,List.of("R1","R3"),SNAPSHOT,semantic).status()).isEqualTo("matched");
        assertThat(expert.search("2A 多少分？","club",null,List.of(),SNAPSHOT,semantic).answers()).isEmpty();
        assertThat(expert.search("2A 多少分？",SCOPE,null,List.of(),SNAPSHOT.plusYears(1),semantic).status()).isEqualTo("non_current");
        assertThat(expert.search("2A 多少分？",SCOPE,"unknown",List.of(),SNAPSHOT,semantic).answers()).isEmpty();
        assertThat(expert.search("2025/26 赛季 2A 的基础分？",SCOPE,null,List.of(),SNAPSHOT,semantic).answers()).isEmpty();
        assertThat(expert.search("我的 3T 能在几天内练成？",SCOPE,null,List.of(),SNAPSHOT,semantic).answers()).isEmpty();
        assertThat(expert.search("两周阿克塞尔的基础分是多少？",SCOPE,null,List.of(),SNAPSHOT,semantic).answers().get(0).text()).contains("3.30");
    }
    @Test void numericLookupRoundTripsEveryVerifiedBaseValueAndPage() {
        var expert=new ExpertRetrieval(corpus,new LocalBge(""));
        for(var f:corpus.pack.facts()) if(f.key().startsWith("bv.")) {
            var row=expert.search(f.key(),SCOPE,null,List.of("R3"),SNAPSHOT,false);
            assertThat(row.status()).as(f.key()).isEqualTo("matched");
            var expected=corpus.pack.locators().get(f.refs().get(0));
            assertThat(row.evidence().get(0).sourceUrl()).as(f.key()).endsWith("#page="+expected.pages().get(0));
            assertThat(row.answers().get(0).text()).as(f.key()).contains(f.value().asText());
        }
        assertThat(corpus.manifest()).containsEntry("factCount",203).containsEntry("passageCount",40);
    }
}
