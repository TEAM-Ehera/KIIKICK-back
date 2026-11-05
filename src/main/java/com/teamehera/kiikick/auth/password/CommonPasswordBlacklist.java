package com.teamehera.kiikick.auth.password;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

@Component
public class CommonPasswordBlacklist {

    static final int EXPECTED_PASSWORD_COUNT = 1_000;

    private final HashSet<String> passwords;
    private final int sourceEntryCount;

    public CommonPasswordBlacklist(
            @Value("classpath:blacklist.txt") Resource blacklistResource
    ) {
        LoadedBlacklist loaded = load(blacklistResource);
        this.passwords = loaded.passwords();
        this.sourceEntryCount = loaded.sourceEntryCount();
    }

    public boolean contains(String password) {
        // 차단 여부만 Locale 독립적으로 비교하고, 실제 BCrypt 해싱에는 원문을 사용한다.
        return passwords.contains(password.toLowerCase(Locale.ROOT));
    }

    int size() {
        return sourceEntryCount;
    }

    private LoadedBlacklist load(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Password blacklist is missing or unreadable");
        }
        HashSet<String> sourceEntries = new HashSet<>();
        HashSet<String> loaded = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    throw new IllegalStateException("Password blacklist contains a blank line");
                }
                sourceEntries.add(line);
                loaded.add(line.toLowerCase(Locale.ROOT));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Password blacklist could not be loaded", exception);
        }
        if (sourceEntries.size() != EXPECTED_PASSWORD_COUNT) {
            throw new IllegalStateException(
                    "Password blacklist must contain exactly "
                            + EXPECTED_PASSWORD_COUNT + " unique entries"
            );
        }
        return new LoadedBlacklist(loaded, sourceEntries.size());
    }

    private record LoadedBlacklist(
            HashSet<String> passwords,
            int sourceEntryCount
    ) {
    }
}
