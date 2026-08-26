package com.financeos.chat.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private boolean enabled = true;
    private Datasource datasource = new Datasource();
    private String appSchema = "ADMIN";
    private Quota quota = new Quota();
    private Loop loop = new Loop();
    private Stream stream = new Stream();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public void setDatasource(Datasource datasource) {
        this.datasource = datasource;
    }

    public String getAppSchema() {
        return appSchema;
    }

    public void setAppSchema(String appSchema) {
        this.appSchema = appSchema;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Loop getLoop() {
        return loop;
    }

    public void setLoop(Loop loop) {
        this.loop = loop;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream;
    }

    public static class Datasource {
        private String username = "";
        private String password = "";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Quota {
        private int messagesPerDay = 30;
        private int maxConcurrent = 2;

        public int getMessagesPerDay() {
            return messagesPerDay;
        }

        public void setMessagesPerDay(int messagesPerDay) {
            this.messagesPerDay = messagesPerDay;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }
    }

    public static class Loop {
        private int maxIterations = 6;
        private int maxWallClockSeconds = 90;
        private int sqlRowCap = 200;
        private int sqlTimeoutSeconds = 5;
        private int resultCharCap = 16000;

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public int getMaxWallClockSeconds() {
            return maxWallClockSeconds;
        }

        public void setMaxWallClockSeconds(int maxWallClockSeconds) {
            this.maxWallClockSeconds = maxWallClockSeconds;
        }

        public int getSqlRowCap() {
            return sqlRowCap;
        }

        public void setSqlRowCap(int sqlRowCap) {
            this.sqlRowCap = sqlRowCap;
        }

        public int getSqlTimeoutSeconds() {
            return sqlTimeoutSeconds;
        }

        public void setSqlTimeoutSeconds(int sqlTimeoutSeconds) {
            this.sqlTimeoutSeconds = sqlTimeoutSeconds;
        }

        public int getResultCharCap() {
            return resultCharCap;
        }

        public void setResultCharCap(int resultCharCap) {
            this.resultCharCap = resultCharCap;
        }
    }

    public static class Stream {
        private int emitterTimeoutSeconds = 290;

        public int getEmitterTimeoutSeconds() {
            return emitterTimeoutSeconds;
        }

        public void setEmitterTimeoutSeconds(int emitterTimeoutSeconds) {
            this.emitterTimeoutSeconds = emitterTimeoutSeconds;
        }
    }
}
