package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import com.evernote.thrift.transport.TTransport;
import com.evernote.thrift.transport.TTransportException;
import hudson.ProxyConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends Thrift messages over HTTP with a {@link HttpClient}, so that Evernote API calls use the Jenkins proxy
 * configuration (unlike the SDK's own HTTP transport). Each {@link #flush()} posts the buffered request and makes
 * the response available to {@link #read}.
 */
final class HttpThriftTransport extends TTransport {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final URI uri;
    private final ByteArrayOutputStream request = new ByteArrayOutputStream();
    private InputStream response;

    HttpThriftTransport(HttpClient httpClient, URI uri) {
        this.httpClient = httpClient;
        this.uri = uri;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void open() {
        // every request is a separate HTTP exchange
    }

    @Override
    public void close() {
        response = null;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws TTransportException {
        if (response == null) {
            throw new TTransportException("No response to read: no request was sent");
        }
        try {
            int read = response.read(buffer, offset, length);
            if (read < 0) {
                throw new TTransportException(TTransportException.END_OF_FILE, "The Evernote response ended early");
            }
            return read;
        } catch (IOException e) {
            throw new TTransportException(e);
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        request.write(buffer, offset, length);
    }

    @Override
    public void flush() throws TTransportException {
        byte[] body = request.toByteArray();
        request.reset();
        HttpRequest httpRequest = ProxyConfiguration.newHttpRequestBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-thrift")
                .header("Accept", "application/x-thrift")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            // Thrift cannot propagate InterruptedException; keep the flag so callers can rethrow it.
            Thread.currentThread().interrupt();
            throw new TTransportException("Interrupted while calling Evernote", e);
        } catch (IOException e) {
            throw new TTransportException("Could not reach Evernote at " + uri + ": " + e, e);
        }
        if (httpResponse.statusCode() != 200) {
            throw new TTransportException("Evernote answered HTTP " + httpResponse.statusCode() + " for " + uri);
        }
        response = new ByteArrayInputStream(httpResponse.body());
    }
}
