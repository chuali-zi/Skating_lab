package com.skatingcoach.knowledge;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TextProcessingTest {
    @Test void keepsJumpCodesAndAddsChineseBigrams() {
        assertThat(TextProcessing.tokens("3S+3T 后半程疲劳")).contains("3s","3t","后半","半程","疲劳");
    }
    @Test void queryOperatorsNeverComeFromUntrustedInput() {
        assertThat(TextProcessing.query("3T'); drop table X; --"))
                .doesNotContain("'",";","--").contains("3t");
    }
    @Test void chunksPreserveEveryCharacterWithoutSplittingSurrogates() {
        String input="3S+3T 跳跃🧊".repeat(600);
        var chunks=TextProcessing.chunks(input,"page=2");
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.stream().map(KnowledgeModels.ParsedPart::text).reduce("",String::concat).replace(" ",""))
                .isEqualTo(input.replace(" ",""));
        assertThat(chunks).allSatisfy(p->assertThat(p.locator()).startsWith("page=2;part="));
    }
}
