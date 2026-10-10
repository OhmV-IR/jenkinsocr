package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.type.Notebook;
import com.evernote.edam.type.User;
import com.evernote.thrift.TBase;
import com.evernote.thrift.TException;
import com.evernote.thrift.protocol.TBinaryProtocol;
import com.evernote.thrift.protocol.TField;
import com.evernote.thrift.protocol.TList;
import com.evernote.thrift.protocol.TMessage;
import com.evernote.thrift.protocol.TMessageType;
import com.evernote.thrift.protocol.TProtocol;
import com.evernote.thrift.protocol.TStruct;
import com.evernote.thrift.protocol.TType;
import com.evernote.thrift.transport.TTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ThriftEvernoteNoteStore} and {@link HttpThriftTransport} against a fake Evernote server that
 * speaks the real EDAM Thrift binary protocol.
 */
class ThriftEvernoteNoteStoreTest {

    /** Writes a Thrift reply for one call. */
    @FunctionalInterface
    private interface Reply {
        void write(TProtocol protocol) throws TException;
    }

    /** An in-memory transport used to decode requests and encode replies. */
    private static final class MemoryTransport extends TTransport {
        private final ByteArrayInputStream in;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        MemoryTransport(byte[] input) {
            in = new ByteArrayInputStream(input);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return Math.max(0, in.read(buffer, offset, length));
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            out.write(buffer, offset, length);
        }
    }

    private HttpServer server;
    private URI base;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, List<Reply>> replies = new ConcurrentHashMap<>();
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                assertEquals(
                        "application/x-thrift", exchange.getRequestHeaders().getFirst("Content-Type"));
                TBinaryProtocol request = new TBinaryProtocol(
                        new MemoryTransport(exchange.getRequestBody().readAllBytes()));
                TMessage message = request.readMessageBegin();
                calls.add(exchange.getRequestURI().getPath() + " " + message.name);
                MemoryTransport response = new MemoryTransport(new byte[0]);
                TBinaryProtocol protocol = new TBinaryProtocol(response);
                protocol.writeMessageBegin(new TMessage(message.name, TMessageType.REPLY, message.seqid));
                replies.get(message.name).remove(0).write(protocol);
                protocol.writeMessageEnd();
                byte[] bytes = response.out.toByteArray();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (TException e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void reply(String method, Reply reply) {
        replies.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(reply);
    }

    /** A successful result struct: field 0 holds the return value. */
    private static Reply success(byte type, Reply value) {
        return protocol -> {
            protocol.writeStructBegin(new TStruct("result"));
            protocol.writeFieldBegin(new TField("success", type, (short) 0));
            value.write(protocol);
            protocol.writeFieldEnd();
            protocol.writeFieldStop();
            protocol.writeStructEnd();
        };
    }

    /** A failed result struct: exception fields are numbered from 1 in the order the IDL declares them. */
    private static Reply failure(short fieldId, TBase<?> exception) {
        return protocol -> {
            protocol.writeStructBegin(new TStruct("result"));
            protocol.writeFieldBegin(new TField("exception", TType.STRUCT, fieldId));
            exception.write(protocol);
            protocol.writeFieldEnd();
            protocol.writeFieldStop();
            protocol.writeStructEnd();
        };
    }

    private void replyWithAccount() {
        reply("getNoteStoreUrl", success(TType.STRING, p -> p.writeString(base + "/shard/s42/notestore")));
        User user = new User();
        user.setId(1234);
        user.setShardId("s42");
        reply("getUser", success(TType.STRUCT, user::write));
    }

    private ThriftEvernoteNoteStore connect() throws Exception {
        HttpClient http =
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        return ThriftEvernoteNoteStore.connect(
                http, base.resolve("/edam/user"), EvernoteService.PRODUCTION, "token", sleeps::add);
    }

    @Test
    void discoversTheNoteStoreAndListsNotebooks() throws Exception {
        replyWithAccount();
        Notebook algebra = new Notebook();
        algebra.setGuid("g1");
        algebra.setName("Algebra");
        algebra.setStack("Math");
        Notebook inbox = new Notebook();
        inbox.setGuid("g2");
        inbox.setName("Inbox");
        inbox.setDefaultNotebook(true);
        reply("listNotebooks", success(TType.LIST, p -> {
            p.writeListBegin(new TList(TType.STRUCT, 2));
            algebra.write(p);
            inbox.write(p);
            p.writeListEnd();
        }));

        ThriftEvernoteNoteStore store = connect();
        assertEquals(
                List.of(
                        new EvernoteNoteStore.Notebook("g1", "Algebra", "Math", false),
                        new EvernoteNoteStore.Notebook("g2", "Inbox", null, true)),
                store.listNotebooks());
        assertEquals(
                List.of("/edam/user getNoteStoreUrl", "/edam/user getUser", "/shard/s42/notestore listNotebooks"),
                calls);
        assertEquals("https://www.evernote.com/shard/s42/nl/1234/note-guid/", store.noteUrl("note-guid"));
    }

    @Test
    void userErrorsExplainWhatWentWrong() throws Exception {
        EDAMUserException expired = new EDAMUserException();
        expired.setErrorCode(EDAMErrorCode.AUTH_EXPIRED);
        expired.setParameter("authenticationToken");
        reply("getNoteStoreUrl", failure((short) 1, expired));
        IOException e = assertThrows(IOException.class, this::connect);
        assertTrue(e.getMessage().contains("AUTH_EXPIRED"), e.getMessage());
        assertTrue(e.getMessage().contains("token is valid and has not expired"), e.getMessage());
    }

    @Test
    void shortRateLimitsAreWaitedOut() throws Exception {
        replyWithAccount();
        EDAMSystemException rateLimited = new EDAMSystemException();
        rateLimited.setErrorCode(EDAMErrorCode.RATE_LIMIT_REACHED);
        rateLimited.setRateLimitDuration(7);
        reply("listNotebooks", failure((short) 2, rateLimited));
        reply("listNotebooks", success(TType.LIST, p -> {
            p.writeListBegin(new TList(TType.STRUCT, 0));
            p.writeListEnd();
        }));
        assertEquals(List.of(), connect().listNotebooks());
        assertEquals(List.of(7000L), sleeps);
    }

    @Test
    void longRateLimitsFailWithTheWaitTime() throws Exception {
        replyWithAccount();
        EDAMSystemException rateLimited = new EDAMSystemException();
        rateLimited.setErrorCode(EDAMErrorCode.RATE_LIMIT_REACHED);
        rateLimited.setRateLimitDuration(900);
        reply("listNotebooks", failure((short) 2, rateLimited));
        ThriftEvernoteNoteStore store = connect();
        IOException e = assertThrows(IOException.class, store::listNotebooks);
        assertTrue(e.getMessage().contains("retrying in 900 seconds"), e.getMessage());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void httpErrorsAreReported() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        IOException e = assertThrows(IOException.class, this::connect);
        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
    }
}
