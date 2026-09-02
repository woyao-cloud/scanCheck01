package com.example.compliance.auth.config

import com.example.compliance.auth.application.JwtService
import com.example.compliance.user.application.UserService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val userService: UserService,
) {
    // NO passwordEncoder() bean here — the single PasswordEncoder bean lives in
    // module-user's PasswordEncoderConfig (Task 1.2, Ruling #21). Defining a second one
    // would fail the context (Boot 3 default disallows bean-definition overriding).

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Ruling #23: without an explicit entry point, HttpSecurity's default is
            // Http403ForbiddenEntryPoint → unauthenticated requests get 403, but a JWT API
            // must answer 401 (constraint 7). AuthIntegrationTest asserts isUnauthorized.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/v1/auth/login",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/actuator/health",
                ).permitAll()
                auth.anyRequest().authenticated()
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtService, userService),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
