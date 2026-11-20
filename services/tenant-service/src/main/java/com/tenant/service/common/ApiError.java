package com.tenant.service.common;

import java.time.Instant;

public record ApiError(String message, Instant timestamp) {
}
