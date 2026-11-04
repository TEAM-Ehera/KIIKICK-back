package com.teamehera.kiikick.auth.password;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

@Component
public class CommonPasswordBlacklist {

    static final int EXPECTED_PASSWORD_COUNT = 1_000;

    private final HashSet<String> passwords;

    public CommonPasswordBlacklist(
            @Value("classpath:blacklist.txt") Resource blacklistResource
    ) {
        this.passwords = load(blacklistResource);
    }

    public boolean contains(String password) {
        // 정책에 따라 대소문자 변환이나 Unicode 정규화 없이 정확히 비교한다.
        return passwords.contains(password);
    }

    int size() {
        return passwords.size();
    }

    private HashSet<String> load(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Password blacklist is missing or unreadable");
        }
        HashSet<String> loaded = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    throw new IllegalStateException("Password blacklist contains a blank line");
                }
                loaded.add(line);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Password blacklist could not be loaded", exception);
        }
        if (loaded.size() != EXPECTED_PASSWORD_COUNT) {
            throw new IllegalStateException(
                    "Password blacklist must contain exactly "
                            + EXPECTED_PASSWORD_COUNT + " unique entries"
            );
        }
        return loaded;
    }
}
