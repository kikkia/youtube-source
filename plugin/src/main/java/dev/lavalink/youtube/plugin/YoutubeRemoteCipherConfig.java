package dev.lavalink.youtube.plugin;

import java.util.Collections;
import java.util.List;

public class YoutubeRemoteCipherConfig {
    private Object url;
    private String password;
    private String userAgent = "yt-source";

    public List<String> getUrls() {
        if (url instanceof List) {
            return (List<String>) url;
        } else if (url instanceof String) {
            return Collections.singletonList((String) url);
        }

        return Collections.emptyList();
    }

    public String getPassword() {
        return password;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUrl(Object url) {
        this.url = url;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

}
