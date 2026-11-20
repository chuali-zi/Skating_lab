package com.skatingcoach.knowledge;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Pinned CPU encoder. Only the public corpus caller caches document vectors. */
@Component
public class LocalBge implements AutoCloseable {
    public static final String MODEL = "BAAI/bge-small-zh-v1.5@75c43b0-int8";
    private static final String PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private final Path directory;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public LocalBge(@Value("${SKATING_BGE_DIR:}") String directory) {
        this.directory=directory.isBlank()?null:Path.of(directory);
    }
    public boolean configured() { return directory!=null; }

    private static void verify(Path path,String expected) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=new DigestInputStream(Files.newInputStream(path),digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("语义模型文件指纹不符。");
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void load() throws IOException,OrtException {
        if (session!=null) return;
        Path model=directory.resolve("model_quantized.onnx"), tokens=directory.resolve("tokenizer.json");
        verify(model,"15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc");
        verify(tokens,"48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26");
        try(var options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            var encoder=HuggingFaceTokenizer.newInstance(tokens,Map.of("truncation","true","maxLength","256","padding","false"));
            try {
                session=OrtEnvironment.getEnvironment().createSession(model.toString(),options);
                tokenizer=encoder;
            } catch(OrtException | RuntimeException e) { encoder.close(); throw e; }
        }
    }

    public synchronized double[] encode(String text,boolean query) {
        if (!configured()) throw new IllegalStateException("本地语义模型未配置。");
        try {
            load();
            var encoding=tokenizer.encode(query?PREFIX+text:text);
            var env=OrtEnvironment.getEnvironment();
            try(var ids=OnnxTensor.createTensor(env,new long[][]{encoding.getIds()});
                var mask=OnnxTensor.createTensor(env,new long[][]{encoding.getAttentionMask()});
                var types=OnnxTensor.createTensor(env,new long[][]{encoding.getTypeIds()});
                var output=session.run(Map.of("input_ids",ids,"attention_mask",mask,"token_type_ids",types),Set.of("last_hidden_state"))) {
                float[] pooled=((float[][][]) output.get(0).getValue())[0][0];
                double norm=0;
                for(float value:pooled) norm+=(double)value*value;
                if (!(norm>0) || !Double.isFinite(norm)) throw new IllegalStateException("语义向量无效。");
                norm=Math.sqrt(norm);
                double[] normalized=new double[pooled.length];
                for(int i=0;i<pooled.length;i++) normalized[i]=pooled[i]/norm;
                return normalized;
            }
        } catch(IOException | OrtException | LinkageError e) { throw new IllegalStateException("本地语义模型暂不可用。",e); }
    }

    @Override @PreDestroy public synchronized void close() throws OrtException {
        if (session!=null) { session.close(); session=null; }
        if (tokenizer!=null) { tokenizer.close(); tokenizer=null; }
    }
}
