package ru.campusrating.identity.internal;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class TemporaryPassword {
    private static final char[] ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private final SecureRandom random=new SecureRandom();
    public String generate() {
        var result=new char[12];
        for(int i=0;i<result.length;i++) result[i]=ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(result);
    }
}
