package com.grash.security;

import com.grash.model.User;
import com.grash.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    @Lazy
    private UserService userService;

    /**
     * An unknown email has to surface as {@link UsernameNotFoundException}, and as nothing else.
     * Spring's provider turns exactly that exception into "bad credentials" and wraps any other
     * one in {@code InternalAuthenticationServiceException} - which {@code UserService.signin}
     * reports as 503, because that is also how a database outage arrives. With the bare
     * {@code Optional.get()} this used to do, an unknown email answered 503 while a known one
     * with a wrong password answered 403, so the login form told anyone which addresses have an
     * account. It also skipped the provider's dummy password check, which keeps the two cases
     * equally slow.
     */
    @Override
    @Transactional(readOnly = true)
    public CustomUserDetail loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userService.findByEmailWithRolesCached(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return CustomUserDetail.builder()//
                .user(user)//
                .build();
    }

}
