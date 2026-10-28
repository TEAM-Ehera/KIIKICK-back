package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.domain.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
}
