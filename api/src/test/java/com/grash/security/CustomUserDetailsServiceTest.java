package com.grash.security;

import com.grash.model.User;
import com.grash.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadUserByUsername_returnsCustomUserDetailForUser() {
        User user = new User();
        user.setEmail("john@test.com");
        when(userService.findByEmailWithRolesCached("john@test.com")).thenReturn(Optional.of(user));

        CustomUserDetail result = customUserDetailsService.loadUserByUsername("john@test.com");

        assertNotNull(result);
        assertSame(user, result.getUser());
        assertEquals("john@test.com", result.getUsername());
    }

    @Test
    void loadUserByUsername_looksTheUserUpThroughTheCache() {
        User user = new User();
        user.setEmail("test@test.com");
        when(userService.findByEmailWithRolesCached("test@test.com")).thenReturn(Optional.of(user));

        customUserDetailsService.loadUserByUsername("test@test.com");

        verify(userService).findByEmailWithRolesCached("test@test.com");
    }

    @Test
    void loadUserByUsername_unknownEmail_throwsUsernameNotFound() {
        when(userService.findByEmailWithRolesCached("nobody@test.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> customUserDetailsService.loadUserByUsername("nobody@test.com"));
    }

    // The two tests below run Spring's own provider, configured as in WebSecurityConfig, because
    // the property that matters is how *it* classifies what this service throws: bad credentials
    // become 403 in UserService.signin, an internal error becomes 503.

    private DaoAuthenticationProvider provider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailsService);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        return provider;
    }

    @Test
    void unknownEmail_isBadCredentials_likeAWrongPassword() {
        when(userService.findByEmailWithRolesCached("nobody@test.com")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> provider().authenticate(
                new UsernamePasswordAuthenticationToken("nobody@test.com", "wrong")));
    }

    @Test
    void databaseFailure_isStillAnInternalError() {
        when(userService.findByEmailWithRolesCached("john@test.com"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThrows(InternalAuthenticationServiceException.class, () -> provider().authenticate(
                new UsernamePasswordAuthenticationToken("john@test.com", "whatever")));
    }
}
