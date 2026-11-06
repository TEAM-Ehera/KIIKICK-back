package com.teamehera.kiikick.user.avatar;

import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.service.MyProfileService.MissingUser;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarService {
    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final AvatarStorage storage;
    private final UserRepository users;
    private final TransactionTemplate transaction;

    public AvatarService(AvatarStorage storage, UserRepository users, PlatformTransactionManager manager) {
        this.storage = storage;
        this.users = users;
        this.transaction = new TransactionTemplate(manager);
    }

    public String upload(UUID owner, MultipartFile file) {
        users.findById(owner).orElseThrow(MissingUser::new);
        String url = storage.upload(owner, AvatarImage.encode(file));
        transaction.executeWithoutResult(status -> {
            // 롤백이 확인된 경우에만 새 파일을 삭제한다. 커밋 결과가 불명확하면 DB 참조를 보존한다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completionStatus) {
                    if (completionStatus == STATUS_ROLLED_BACK) deleteQuietly(owner, url);
                }
            });
            replace(owner, url);
        });
        return url;
    }

    public void remove(UUID owner) {
        transaction.executeWithoutResult(status -> replace(owner, null));
    }

    private void replace(UUID owner, String url) {
        var current = users.findByIdForUpdate(owner).orElseThrow(MissingUser::new);
        if (current.avatarUrl() == null && url == null) return;
        users.updateAvatar(owner, url);
        retireAfterCommit(owner, current.avatarUrl());
    }

    public boolean isManagedUrl(String url) {
        return storage.manages(url);
    }

    public void retireAfterCommit(UUID owner, String url) {
        if (!storage.manages(url)) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(owner, url);
            }
        });
    }

    private void deleteQuietly(UUID owner, String url) {
        try {
            storage.delete(owner, url);
        } catch (AvatarStorage.Unavailable exception) {
            log.warn("R2 avatar cleanup failed for {}", url, exception);
        }
    }
}
