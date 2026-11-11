package com.financeos.sms.device;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates a request from "Authorization: Bearer <device token>" as the device's user. Only ever installed in the
 * chain that covers the device endpoints, so a device token authenticates nothing else. Without a valid token the
 * request stays anonymous (and is refused with 401), whatever else it carries.
 */
public class SmsDeviceAuthFilter extends OncePerRequestFilter {

    public static final String DEVICE_AUTHORITY = "ROLE_SMS_DEVICE";
    private static final String PREFIX = "Bearer ";

    private final SmsDeviceService devices;

    public SmsDeviceAuthFilter(SmsDeviceService devices) {
        this.devices = devices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            Optional<SmsDeviceService.DeviceAuth> auth = devices.authenticate(header.substring(PREFIX.length()));
            if (auth.isPresent()) {
                UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                        auth.get().userEmail(), null, List.of(new SimpleGrantedAuthority(DEVICE_AUTHORITY)));
                token.setDetails(auth.get().deviceId());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(token);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
