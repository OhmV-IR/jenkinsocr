package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import java.net.URI;

/**
 * The Evernote service an account belongs to.
 */
public enum EvernoteService {
    PRODUCTION("Evernote", "https://www.evernote.com"),
    YINXIANG("Yinxiang Biji (印象笔记)", "https://app.yinxiang.com"),
    SANDBOX("Evernote developer sandbox", "https://sandbox.evernote.com");

    private final String displayName;
    private final String baseUrl;

    EvernoteService(String displayName, String baseUrl) {
        this.displayName = displayName;
        this.baseUrl = baseUrl;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * The endpoint of the UserStore service, which tells clients where the account's NoteStore is.
     */
    URI userStoreUri() {
        return URI.create(baseUrl + "/edam/user");
    }

    /**
     * The web link of a note.
     */
    String noteUrl(String shardId, int userId, String noteGuid) {
        return baseUrl + "/shard/" + shardId + "/nl/" + userId + "/" + noteGuid + "/";
    }
}
