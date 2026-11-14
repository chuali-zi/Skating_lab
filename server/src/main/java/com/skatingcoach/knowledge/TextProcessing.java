package com.skatingcoach.knowledge;

import java.util.*;
import java.util.regex.*;
import static com.skatingcoach.knowledge.KnowledgeModels.*;

/** Lightweight multilingual lexical fallback. Exact jump sequences are kept in the original text. */
public final class TextProcessing {
    private static final Pattern WORDS=Pattern.compile("[a-zA-Z0-9_]+|[\\p{IsHan}]+",Pattern.UNICODE_CHARACTER_CLASS);
    private TextProcessing(){}
    public static List<String> tokens(String text) {
        var result=new LinkedHashSet<String>();
        Matcher matcher=WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            String word=matcher.group();
            if(word.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                int[] points=word.codePoints().toArray();
                if(points.length==1) result.add(word);
                for(int i=0;i<points.length-1;i++) result.add(new String(points,i,2));
            } else result.add(word);
        }
        return List.copyOf(result);
    }
    public static String searchable(String text){return String.join(" ",tokens(text));}
    public static String query(String text){return String.join(" | ",tokens(text));}
    public static List<ParsedPart> chunks(String text,String locator) {
        List<ParsedPart> parts=new ArrayList<>();
        String clean=text.replace('\u0000',' ').strip();
        int start=0,part=1;
        while(start<clean.length()) {
            int end=Math.min(start+1800,clean.length());
            if(end<clean.length()) {
                int paragraph=clean.lastIndexOf('\n',end);
                if(paragraph>start+600) end=paragraph;
                if(end>start&&Character.isHighSurrogate(clean.charAt(end-1))) end--;
            }
            String block=clean.substring(start,end).strip();
            if(!block.isEmpty()) parts.add(new ParsedPart(block,locator+";part="+part++));
            if(end==clean.length()) break;
            start=end;
        }
        return parts;
    }
}
