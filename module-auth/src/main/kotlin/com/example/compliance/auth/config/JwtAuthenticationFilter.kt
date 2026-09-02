package com.example.compliance.auth.config

import com.example.compliance.auth.application.JwtService
import com.example.compliance.user.application.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

// Ruling #28: NOT a @Component. Spring Boot auto-registers every Filter bean as a servlet-level
// filter OUTSIDE the FilterChainProxy; SecurityConfig also constructs this class and adds it to the
// security chain via addFilterBefore — a @Component would run the JWT logic twice per Bearer request
// (double parse + 2x findByUsername/findRoles). The manual chain registration covers ordering; the
// filter is not injected anywhere else.
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userService: UserService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            try {
                val claims = jwtService.parse(header.removePrefix("Bearer ")).payload
                val user = userService.findByUsername(claims.subject)
                if (user != null && user.status == "ACTIVE") {
                    val authorities = userService.findRoles(user.id!!)
                        .map { SimpleGrantedAuthority("ROLE_" + it.code) }
                    val authentication = UsernamePasswordAuthenticationToken(
                        user.username, null, authorities,
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                }
            } catch (e: Exception) {
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }
}
