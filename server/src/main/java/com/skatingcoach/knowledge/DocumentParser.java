package com.skatingcoach.knowledge;

import java.io.*;
import java.util.*;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import static com.skatingcoach.knowledge.KnowledgeModels.*;

@Component
public class DocumentParser {
    public List<ParsedPart> parse(InputStream input)throws Exception {
        var handler=new StructuredText();
        new AutoDetectParser().parse(input,handler,new Metadata(),new ParseContext());
        handler.flush();
        return List.copyOf(handler.parts);
    }
    /** Preserve Tika's page boundaries and row separators; originals remain available for visual review. */
    static class StructuredText extends DefaultHandler {
        final List<ParsedPart> parts=new ArrayList<>(); final StringBuilder text=new StringBuilder();
        int page=0;
        @Override public void startElement(String uri,String local,String qName,Attributes attrs) {
            String tag=local.isEmpty()?qName:local;
            if("div".equals(tag)&&"page".equals(attrs.getValue("class"))) {flush();page++;}
            if(Set.of("p","tr","h1","h2","h3","li").contains(tag))text.append('\n');
            if("td".equals(tag)||"th".equals(tag))text.append('\t');
        }
        @Override public void characters(char[] chars,int start,int length){text.append(chars,start,length);}
        @Override public void endElement(String uri,String local,String qName) {
            if(Set.of("p","tr","h1","h2","h3","li").contains(local))text.append('\n');
        }
        void flush(){parts.addAll(TextProcessing.chunks(text.toString(),page>0?"page="+page:"document"));text.setLength(0);}
    }
}
