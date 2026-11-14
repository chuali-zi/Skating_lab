package com.skatingcoach.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional hosted embedding adapter. Unconfigured providers yield an explicit lexical fallback. */
@Component
public class EmbeddingGateway {
    private final String base,key,model; private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public EmbeddingGateway(ObjectMapper json,@Value("${EMBEDDING_BASE_URL:}")String base,
            @Value("${EMBEDDING_API_KEY:}")String key,@Value("${EMBEDDING_MODEL:}")String model) {
        this.json=json;this.base=base;this.key=key;this.model=model;
    }
    public boolean available(){return !base.isBlank()&&!key.isBlank()&&!model.isBlank();}
    public String model(){return base.replaceAll("/+$","")+"#"+model;}
    public List<double[]> embed(List<String> texts) {
        if(!available())return List.of();
        try {
            URI endpoint=URI.create(base.replaceAll("/+$","")+"/embeddings");
            if(!"https".equals(endpoint.getScheme())&&!Set.of("localhost","127.0.0.1","::1").contains(endpoint.getHost()))
                throw new IllegalStateException("Embedding endpoint must use HTTPS");
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model",model,"input",texts)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("Embedding provider unavailable");
            var data=json.readTree(response.body()).path("data");
            if(!data.isArray()||data.size()!=texts.size())throw new IllegalStateException("Embedding result count mismatch");
            double[][] vectors=new double[texts.size()][];
            for(var item:data) {
                int index=item.path("index").asInt(-1); var values=item.path("embedding");
                if(index<0||index>=vectors.length||vectors[index]!=null||!values.isArray()||values.isEmpty()||values.size()>16000)
                    throw new IllegalStateException("Invalid embedding shape");
                double[] vector=new double[values.size()];
                for(int j=0;j<vector.length;j++) {vector[j]=values.get(j).asDouble(Double.NaN);if(!Double.isFinite(vector[j]))throw new IllegalStateException("Invalid embedding value");}
                vectors[index]=vector;
            }
            return Arrays.asList(vectors);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Embedding interrupted",e);}
        catch(Exception e){throw new IllegalStateException("Embedding unavailable",e);}
    }
    public static String literal(double[] vector){return Arrays.toString(vector);}
}
