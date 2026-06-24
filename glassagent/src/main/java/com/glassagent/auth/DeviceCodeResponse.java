package com.glassagent.auth;

public final class DeviceCodeResponse {
    public final String deviceCode;
    public final String userCode;
    public final String verificationUri;
    public final int expiresIn;
    public final int interval;

    public DeviceCodeResponse(String deviceCode, String userCode, String verificationUri,
                              int expiresIn, int interval) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.verificationUri = verificationUri;
        this.expiresIn = expiresIn;
        this.interval = interval;
    }
}
