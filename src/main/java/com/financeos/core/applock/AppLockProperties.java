package com.financeos.core.applock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code app.app-lock.*}: the relying party (UI host) and every timing/limit knob of the app lock. */
@Component
@ConfigurationProperties(prefix = "app.app-lock")
public class AppLockProperties {

    private String rpId = "localhost";
    private String rpName = "FinanceOS";
    /** Comma-separated exact UI origins. */
    private String origins = "https://localhost:6970";
    private int heartbeatSeconds = 15;
    private int immediateBackstopSeconds = 30;
    private int maxUnlockFailures = 5;
    private int lockIgnoreAfterUnlockSeconds = 2;
    private List<Integer> allowedLockAfterSeconds = new ArrayList<>(List.of(0, 60, 300, 900));
    private int defaultLockAfterSeconds = 60;

    public Set<String> originSet() {
        return java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowedLockAfter(Integer seconds) {
        return seconds != null && allowedLockAfterSeconds.contains(seconds);
    }

    public String getRpId() { return rpId; }
    public void setRpId(String rpId) { this.rpId = rpId; }
    public String getRpName() { return rpName; }
    public void setRpName(String rpName) { this.rpName = rpName; }
    public String getOrigins() { return origins; }
    public void setOrigins(String origins) { this.origins = origins; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int heartbeatSeconds) { this.heartbeatSeconds = heartbeatSeconds; }
    public int getImmediateBackstopSeconds() { return immediateBackstopSeconds; }
    public void setImmediateBackstopSeconds(int v) { this.immediateBackstopSeconds = v; }
    public int getMaxUnlockFailures() { return maxUnlockFailures; }
    public void setMaxUnlockFailures(int maxUnlockFailures) { this.maxUnlockFailures = maxUnlockFailures; }
    public int getLockIgnoreAfterUnlockSeconds() { return lockIgnoreAfterUnlockSeconds; }
    public void setLockIgnoreAfterUnlockSeconds(int v) { this.lockIgnoreAfterUnlockSeconds = v; }
    public List<Integer> getAllowedLockAfterSeconds() { return allowedLockAfterSeconds; }
    public void setAllowedLockAfterSeconds(List<Integer> v) { this.allowedLockAfterSeconds = v; }
    public int getDefaultLockAfterSeconds() { return defaultLockAfterSeconds; }
    public void setDefaultLockAfterSeconds(int v) { this.defaultLockAfterSeconds = v; }
}
