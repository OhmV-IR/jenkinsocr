package io.ohmvir.plugins.jenkinsocr.notes;

/**
 * The outcome of uploading a {@link Note}.
 *
 * @param location where the note can be found, such as a URL or a file path
 * @param appended {@code true} if the content was appended to an existing note with the same title
 */
public record NoteUploadResult(String location, boolean appended) {}
