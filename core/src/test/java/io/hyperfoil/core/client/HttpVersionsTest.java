package io.hyperfoil.core.client;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.hyperfoil.api.connection.HttpClientPool;
import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.http.HttpMethod;
import io.hyperfoil.api.http.HttpResponseHandlers;
import io.hyperfoil.api.http.HttpVersion;
import io.hyperfoil.api.session.SequenceInstance;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.core.builders.HttpBuilder;
import io.hyperfoil.core.client.netty.HttpClientPoolImpl;
import io.hyperfoil.core.session.SessionFactory;
import io.hyperfoil.core.steps.HttpResponseHandlersImpl;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class HttpVersionsTest {
   private static final List<io.vertx.core.http.HttpVersion> HTTP1x_ONLY = Collections.singletonList(io.vertx.core.http.HttpVersion.HTTP_1_1);
   private static final List<io.vertx.core.http.HttpVersion> HTTP2_ONLY = Collections.singletonList(io.vertx.core.http.HttpVersion.HTTP_2);
   private Vertx vertx = Vertx.vertx();

   @Test
   public void testAlpnUpgrade(TestContext ctx) throws Exception {
      test(ctx, 8443, true, HttpVersion.ALL_VERSIONS, HTTP2_ONLY, 200);
   }

   @Test
   public void testAlpnKeep(TestContext ctx) throws Exception {
      test(ctx, 8443, true, HttpVersion.ALL_VERSIONS, HTTP1x_ONLY, 500);
   }

   @Test
   public void testAlpnForceHttp2(TestContext ctx) throws Exception {
      test(ctx, 8443, true, new HttpVersion[]{HttpVersion.HTTP_2_0}, HTTP2_ONLY, 200);
   }

   @Test
   public void testAlpnForceHttp2ServerKeep(TestContext ctx) throws Exception {
      test(ctx, 8443, true, new HttpVersion[]{HttpVersion.HTTP_2_0}, HTTP1x_ONLY, 500);
   }

   @Test
   public void testAlpnForceHttp1x(TestContext ctx) throws Exception {
      test(ctx, 8443, true, new HttpVersion[]{HttpVersion.HTTP_1_1}, HTTP2_ONLY, 500);
   }

   @Test
   public void testH2cUpgrade(TestContext ctx) throws Exception {
      test(ctx, 8080, false, new HttpVersion[]{HttpVersion.HTTP_2_0}, HTTP2_ONLY, 200);
   }

   @Test
   public void testCleartextDefault(TestContext ctx) throws Exception {
      test(ctx, 8080, false, HttpVersion.ALL_VERSIONS, HTTP2_ONLY, 500);
   }
   @Test
   public void testCleartextDefaultServer1x(TestContext ctx) throws Exception {
      test(ctx, 8080, false, HttpVersion.ALL_VERSIONS, HTTP1x_ONLY, 500);
   }

   @Test
   public void testCleartextForceHttp1x(TestContext ctx) throws Exception {
      test(ctx, 8080, false, new HttpVersion[]{HttpVersion.HTTP_1_1}, HTTP2_ONLY, 500);
   }

   private void test(TestContext ctx, int port, boolean ssl, HttpVersion[] clientVersions, List<io.vertx.core.http.HttpVersion> serverVersions, int expectedStatus) throws Exception {
      Async async = ctx.async();
      server(port, ssl, serverVersions, event -> {
         if (event.failed()) {
            ctx.fail(event.cause());
         } else {
            HttpServer server = event.result();
            try {
               HttpClientPool client = client(port, ssl, clientVersions);
               client.start(result -> {
                  if (result.failed()) {
                     ctx.fail(result.cause());
                     return;
                  }
                  Session session = SessionFactory.forTesting();
                  Request request = session.requestPool().acquire();
                  HttpResponseHandlers handlers = HttpResponseHandlersImpl.Builder.forTesting()
                        .statusExtractor((r, status) -> {
                           if (status != expectedStatus) {
                              ctx.fail();
                           }
                        })
                        .onCompletion(s -> {
                           client.shutdown();
                           server.close();
                           async.complete();
                        }).build();
                  request.start(handlers, new SequenceInstance());

                  client.next().request(request, HttpMethod.GET, s -> "/ping", null, null);
               });
            } catch (Exception e) {
               ctx.fail(e);
            }
         }
      });
   }

   private HttpClientPool client(int port, boolean ssl, HttpVersion[] versions) throws Exception {
      HttpBuilder builder = HttpBuilder.forTesting()
            .baseUrl((ssl ? "https" : "http") + "://localhost:" + port);
      builder.allowHttp2(Stream.of(versions).anyMatch(v -> v == HttpVersion.HTTP_2_0));
      builder.allowHttp1x(Stream.of(versions).anyMatch(v -> v == HttpVersion.HTTP_1_1));
      return new HttpClientPoolImpl(1, builder.build(true));
   }

   private void server(int port, boolean ssl, List<io.vertx.core.http.HttpVersion> serverVersions, Handler<AsyncResult<HttpServer>> handler) {
      HttpServer httpServer;
      if (ssl) {
         JksOptions keyStoreOptions = new JksOptions().setPath("keystore.jks").setPassword("test123");
         HttpServerOptions httpServerOptions = new HttpServerOptions()
               .setSsl(true)
               .setKeyStoreOptions(keyStoreOptions)
               .setUseAlpn(true)
               .setAlpnVersions(serverVersions);
         httpServer = vertx.createHttpServer(httpServerOptions);
         httpServer.requestHandler(HttpVersionsTest::requireHttp2).listen(port, "localhost", handler);
      } else {
         httpServer = vertx.createHttpServer();
         httpServer.requestHandler(HttpVersionsTest::requireHttp2).listen(port, "localhost", handler);
      }
   }

   private static void requireHttp2(HttpServerRequest req) {
      if (req.version() != io.vertx.core.http.HttpVersion.HTTP_2) {
         req.response().setStatusCode(500).end("HTTP/2 required.");
      } else {
         req.response().setStatusCode(200).end("Hello");
      }
   }
}