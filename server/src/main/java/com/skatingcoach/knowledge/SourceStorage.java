package com.skatingcoach.knowledge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local private object-store adapter. Storage keys are generated UUIDs, never upload filenames. */
@Component
public class SourceStorage {
    private final Path root;
    public SourceStorage(@Value("${KNOWLEDGE_STORAGE_PATH:./storage/knowledge}") String directory)throws IOException {
        root=Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
    }
    public Stored put(InputStream input)throws IOException {
        String key=UUID.randomUUID().toString(); Path target=resolve(key);
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream hashing=new DigestInputStream(input,digest)) {Files.copy(hashing,target);}
            return new Stored(key,HexFormat.of().formatHex(digest.digest()));
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        catch(IOException e){Files.deleteIfExists(target);throw e;}
    }
    public InputStream open(String key)throws IOException{return Files.newInputStream(resolve(key));}
    public void delete(String key)throws IOException{if(key!=null)Files.deleteIfExists(resolve(key));}
    private Path resolve(String key) {
        UUID.fromString(key);
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root))throw new IllegalArgumentException("Invalid storage key");
        return path;
    }
    public record Stored(String key,String hash){}
}
