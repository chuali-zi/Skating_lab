package com.skatingcoach.knowledge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentParserTest {
    static byte[] syntheticVideo() throws Exception {
        try(var input=DocumentParserTest.class.getResourceAsStream("/fixtures/synthetic-video.mp4.base64")) {
            return Base64.getMimeDecoder().decode(input.readAllBytes());
        }
    }
    @Test void videoContainerMetadataIsNeverAthleteEvidence() throws Exception {
        assertThat(new DocumentParser().parse(new ByteArrayInputStream(syntheticVideo()))).isEmpty();
    }
    @Test void detectionPreservesTheCompleteDocumentInput() throws Exception {
        var parts=new DocumentParser().parse(new ByteArrayInputStream("训练笔记：3S+2T 后半程保持稳定。".getBytes(StandardCharsets.UTF_8)));
        assertThat(parts).singleElement().satisfies(part->assertThat(part.text()).contains("训练笔记：3S+2T 后半程保持稳定。"));
    }
}
