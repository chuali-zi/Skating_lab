package com.skatingcoach.knowledge;

import com.skatingcoach.access.CurrentActorService;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.skatingcoach.knowledge.KnowledgeModels.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final CurrentActorService actors;private final KnowledgeService knowledge;
    private final ExpertCorpus corpus;
    private final ExpertRetrieval expert;
    public KnowledgeController(CurrentActorService actors,KnowledgeService knowledge,ExpertCorpus corpus,ExpertRetrieval expert){
        this.actors=actors;this.knowledge=knowledge;this.corpus=corpus;this.expert=expert;
    }
    @GetMapping("/expert/catalog") public Map<String,Object> expertCatalog(){actors.requireActor();return corpus.manifest();}
    @GetMapping("/expert") public ExpertRetrieval.Result ask(@RequestParam String q,@RequestParam(required=false) String competitionKey,
            @RequestParam(required=false) String revision,@RequestParam(required=false) List<String> sources) {
        actors.requireActor();return expert.ask(q,competitionKey,revision,sources);
    }
    @GetMapping public Map<String,Object> list(){return Map.of("items",knowledge.list(actors.requireActor()));}
    @GetMapping("/catalog") public Map<String,Object> catalog(){actors.requireActor();return Map.of("items",knowledge.catalog());}
    @GetMapping("/{id}") public Detail detail(@PathVariable UUID id){return knowledge.detail(actors.requireActor(),id);}
    @GetMapping("/evidence/{id}") public Evidence evidence(@PathVariable UUID id){
        var actor=actors.requireActor();
        var publicItem=corpus.byId.get(id);
        return publicItem==null?knowledge.evidence(actor,id):publicItem;
    }
    @PostMapping("/text") public Source text(@RequestBody TextInput input){return knowledge.createText(actors.requireActor(),input);}
    @PostMapping("/upload") public Source upload(@RequestParam MultipartFile file,@RequestParam(required=false)String title,
            @RequestParam(required=false)String competitionKey,@RequestParam(required=false)String requestId,
            @RequestParam(required=false)String sourceUrl)throws IOException {
        return knowledge.upload(actors.requireActor(),title==null?file.getOriginalFilename():title,file.getContentType(),competitionKey,requestId,sourceUrl,file.getInputStream());
    }
    @GetMapping("/search") public SearchResult search(@RequestParam(defaultValue="")String q,@RequestParam(required=false)String competitionKey,
            @RequestParam(defaultValue="10")int limit){return knowledge.search(actors.requireActor(),q,competitionKey,limit);}
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable UUID id){return knowledge.delete(actors.requireActor(),id);}
}
