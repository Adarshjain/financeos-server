package com.financeos.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppConfigProperties {

    private Encryption encryption = new Encryption();
    private Cors cors = new Cors();
    private Invite invite = new Invite();
    private Admin admin = new Admin();
    private Diagnostics diagnostics = new Diagnostics();

    public Encryption getEncryption() {
        return encryption;
    }

    public void setEncryption(Encryption encryption) {
        this.encryption = encryption;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Invite getInvite() {
        return invite;
    }

    public void setInvite(Invite invite) {
        this.invite = invite;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public static class Admin {
        private java.util.List<String> emails = new java.util.ArrayList<>();

        public java.util.List<String> getEmails() {
            return emails;
        }

        public void setEmails(java.util.List<String> emails) {
            this.emails = emails;
        }
    }

    public static class Diagnostics {
        private Loki loki = new Loki();

        public Loki getLoki() {
            return loki;
        }

        public void setLoki(Loki loki) {
            this.loki = loki;
        }

        public static class Loki {
            private String url;
            private String user;
            private String token;
            private int lookbackDays = 14;
            private int timeoutSeconds = 10;
            private int maxLines = 500;

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getUser() {
                return user;
            }

            public void setUser(String user) {
                this.user = user;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }

            public int getLookbackDays() {
                return lookbackDays;
            }

            public void setLookbackDays(int lookbackDays) {
                this.lookbackDays = lookbackDays;
            }

            public int getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }

            public int getMaxLines() {
                return maxLines;
            }

            public void setMaxLines(int maxLines) {
                this.maxLines = maxLines;
            }
        }
    }

    public static class Encryption {
        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:3001";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Invite {
        private String code;
        private int maxFailures = 10;
        private int lockoutMinutes = 15;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public int getLockoutMinutes() {
            return lockoutMinutes;
        }

        public void setLockoutMinutes(int lockoutMinutes) {
            this.lockoutMinutes = lockoutMinutes;
        }
    }
}

