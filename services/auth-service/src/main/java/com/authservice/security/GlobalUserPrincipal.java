package com.authservice.security;

import java.util.UUID;

public record GlobalUserPrincipal(UUID globalUserId, String email) {
}
