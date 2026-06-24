package com.glassagent.auth;

public final class TokenResponse {
    public final String accessToken;
    public final String refreshToken;
    public final int expiresIn;
    public final long expiresAt;

    public TokenResponse(String accessToken, String refreshToken, int expiresIn) {
        this(accessToken, refreshToken, expiresIn,
                System.currentTimeMillis() + (Math.max(0, expiresIn) * 1000L));
    }

    public TokenResponse(String accessToken, String refreshToken, int expiresIn, long expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.expiresAt = expiresAt;
    }
}
