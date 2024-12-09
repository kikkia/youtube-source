package dev.lavalink.youtube.clients.skeleton;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.ThumbnailTools;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.OptionDisabledException;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.track.TemporalInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * The base class for a client that is used for everything except music.youtube.com.
 * This class is deprecated.
 * Extend the non-thumbnail counterpart and override the {@link Client#buildAudioTrack(YoutubeAudioSourceManager, JsonBrowser, String, String, long, String, boolean)}
 * method instead.
 */
public abstract class ThumbnailNonMusicClient extends NonMusicClient {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailNonMusicClient.class);

    protected void extractPlaylistTracks(@NotNull JsonBrowser json,
                                         @NotNull List<AudioTrack> tracks,
                                         @NotNull YoutubeAudioSourceManager source) {
        if (!json.get("contents").isNull()) {
            json = json.get("contents");
        }

        if (json.isNull()) {
            return;
        }

        for (JsonBrowser track : json.values()) {
            JsonBrowser item = track.get("playlistVideoRenderer");
            JsonBrowser authorJson = item.get("shortBylineText");

            // isPlayable is null -> video has been removed/blocked
            // author is null -> video is region blocked
            if (!item.get("isPlayable").isNull() && !authorJson.isNull()) {
                String videoId = item.get("videoId").text();
                JsonBrowser titleField = item.get("title");
                String title = DataFormatTools.defaultOnNull(titleField.get("simpleText").text(), titleField.get("runs").index(0).get("text").text());
                String author = DataFormatTools.defaultOnNull(authorJson.get("runs").index(0).get("text").text(), "Unknown artist");
                long duration = Units.secondsToMillis(item.get("lengthSeconds").asLong(Units.DURATION_SEC_UNKNOWN));
                String thumbnailUrl = ThumbnailTools.getYouTubeThumbnail(item, videoId);

                AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false, WATCH_URL + videoId, thumbnailUrl, null);
                tracks.add(source.buildAudioTrack(info));
            }
        }
    }

    @Nullable
    protected AudioTrack extractAudioTrack(@NotNull JsonBrowser json,
                                           @NotNull YoutubeAudioSourceManager source) {
        // Ignore if it's not a track or if it's a livestream
        if (json.isNull() || json.get("lengthText").isNull() || !json.get("unplayableText").isNull()) return null;

        String videoId = json.get("videoId").text();
        JsonBrowser titleJson = json.get("title");
        String title = DataFormatTools.defaultOnNull(titleJson.get("runs").index(0).get("text").text(), titleJson.get("simpleText").text());
        String author = json.get("longBylineText").get("runs").index(0).get("text").text();

        if (author == null) {
            log.debug("Author field is null, client: {}, json: {}", getIdentifier(), json.format());
            author = "Unknown artist";
        }

        JsonBrowser durationJson = json.get("lengthText");
        String durationText = DataFormatTools.defaultOnNull(durationJson.get("runs").index(0).get("text").text(), durationJson.get("simpleText").text());

        long duration = DataFormatTools.durationTextToMillis(durationText);
        String thumbnailUrl = ThumbnailTools.getYouTubeThumbnail(json, videoId);

        AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false, WATCH_URL + videoId, thumbnailUrl, null);
        return source.buildAudioTrack(info);
    }

    @Override
    public AudioItem loadVideo(@NotNull YoutubeAudioSourceManager source,
                               @NotNull HttpInterface httpInterface,
                               @NotNull String videoId) throws CannotBeLoaded, IOException {
        if (!getOptions().getVideoLoading()) {
            throw new OptionDisabledException("Video loading is disabled for this client");
        }

        JsonBrowser json = loadTrackInfoFromInnertube(source, httpInterface, videoId, null, false);
        JsonBrowser playabilityStatus = json.get("playabilityStatus");
        JsonBrowser videoDetails = json.get("videoDetails");

        String title = videoDetails.get("title").text();
        String author = videoDetails.get("author").text();

        if (author == null) {
            log.debug("Author field is null, client: {}, json: {}", getIdentifier(), json.format());
            author = "Unknown artist";
        }

        TemporalInfo temporalInfo = TemporalInfo.fromRawData(playabilityStatus, videoDetails);
        String thumbnailUrl = ThumbnailTools.getYouTubeThumbnail(videoDetails, videoId);

        AudioTrackInfo info = new AudioTrackInfo(title, author, temporalInfo.durationMillis, videoId, temporalInfo.isActiveStream, WATCH_URL + videoId, thumbnailUrl, null);
        return source.buildAudioTrack(info);
    }
}
