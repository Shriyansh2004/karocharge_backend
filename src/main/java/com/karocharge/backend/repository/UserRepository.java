package com.karocharge.backend.repository;

import com.karocharge.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUserName(String userName);


    // This allows the AuthController to check if an email is already taken
    Optional<User> findByEmail(String email);

    // This allows the login logic to find the specific user by email
    Optional<User> findByEmailAndPassword(String email, String password);
}