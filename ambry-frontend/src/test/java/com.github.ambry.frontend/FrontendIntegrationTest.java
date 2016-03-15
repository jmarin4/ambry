package com.github.ambry.frontend;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.rest.NettyClient;
import com.github.ambry.rest.RestServer;
import com.github.ambry.rest.RestTestUtils;
import com.github.ambry.rest.RestUtils;
import com.github.ambry.utils.Utils;
import com.github.ambry.utils.UtilsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Integration tests for Ambry frontend.
 */
public class FrontendIntegrationTest {
  private static final int SERVER_PORT = 1174;
  private static final ClusterMap CLUSTER_MAP;

  static {
    try {
      CLUSTER_MAP = new MockClusterMap();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RestServer ambryRestServer = null;
  private static NettyClient nettyClient = null;

  /**
   * Sets up an Ambry frontend server.
   * @throws InstantiationException
   * @throws InterruptedException
   */
  @BeforeClass
  public static void setup()
      throws InstantiationException, InterruptedException {
    ambryRestServer = new RestServer(buildFrontendVProps(), CLUSTER_MAP, new LoggingNotificationSystem());
    ambryRestServer.start();
    nettyClient = new NettyClient("localhost", SERVER_PORT);
  }

  /**
   * Shuts down the Ambry frontend server.
   */
  @AfterClass
  public static void teardown() {
    if (nettyClient != null) {
      nettyClient.close();
    }
    if (ambryRestServer != null) {
      ambryRestServer.shutdown();
    }
  }

  /**
   * Tests blob POST, GET, HEAD and DELETE operations.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  @Test
  public void postGetHeadDeleteTest()
      throws ExecutionException, InterruptedException {
    ByteBuffer content = ByteBuffer.wrap(RestTestUtils.getRandomBytes(1024));
    String serviceId = "postGetHeadDeleteServiceID";
    String contentType = "application/octet-stream";
    String ownerId = "postGetHeadDeleteOwnerID";
    HttpHeaders headers = new DefaultHttpHeaders();
    setAmbryHeaders(headers, content.capacity(), 7200, false, serviceId, contentType, ownerId);
    headers.set(HttpHeaders.Names.CONTENT_LENGTH, content.capacity());
    headers.add(RestUtils.Headers.USER_META_DATA_HEADER_PREFIX + "key1", "value1");
    headers.add(RestUtils.Headers.USER_META_DATA_HEADER_PREFIX + "key2", "value2");

    String blobId = postBlobAndVerify(headers, content);
    getBlobAndVerify(blobId, headers, content);
    getHeadAndVerify(blobId, headers, null);
    deleteBlobAndVerify(blobId);

    // check GET, HEAD and DELETE after delete.
    verifyOperationsAfterDelete(blobId);
  }

  /**
   * Tests multipart POST and verifies it via GET operations.
   * @throws Exception
   */
  @Test
  public void multipartPostGetHeadTest()
      throws Exception {
    ByteBuffer blobContent = ByteBuffer.wrap(RestTestUtils.getRandomBytes(1024));
    ByteBuffer usermetadata = ByteBuffer.wrap(UtilsTest.getRandomString(32).getBytes());
    String serviceId = "postGetHeadDeleteServiceID";
    String contentType = "application/octet-stream";
    String ownerId = "postGetHeadDeleteOwnerID";
    HttpHeaders headers = new DefaultHttpHeaders();
    setAmbryHeaders(headers, blobContent.capacity(), 7200, false, serviceId, contentType, ownerId);

    String blobId = multipartPostBlobAndVerify(headers, blobContent, usermetadata);
    getBlobAndVerify(blobId, headers, blobContent);
    getHeadAndVerify(blobId, headers, usermetadata);
  }

  // helpers
  // general

  /**
   * Method to easily create a request.
   * @param httpMethod the {@link HttpMethod} desired.
   * @param uri string representation of the desired URI.
   * @param headers any associated headers as a {@link HttpHeaders} object. Can be null.
   * @param content the content that accompanies the request. Can be null.
   * @return A {@link FullHttpRequest} object that defines the request required by the input.
   */
  private FullHttpRequest buildRequest(HttpMethod httpMethod, String uri, HttpHeaders headers, ByteBuffer content) {
    ByteBuf contentBuf;
    if (content != null) {
      contentBuf = Unpooled.wrappedBuffer(content);
    } else {
      contentBuf = Unpooled.buffer(0);
    }
    FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, uri, contentBuf);
    if (headers != null) {
      httpRequest.headers().set(headers);
    }
    return httpRequest;
  }

  /**
   * Combines all the parts in {@code contents} into one {@link ByteBuffer}.
   * @param response the {@link HttpResponse} containing headers.
   * @param contents the content of the response.
   * @return a {@link ByteBuffer} that contains all the data in {@code contents}.
   */
  private ByteBuffer getContent(HttpResponse response, Queue<HttpObject> contents) {
    long contentLength = HttpHeaders.getContentLength(response, -1);
    if (contentLength == -1) {
      contentLength = HttpHeaders.getIntHeader(response, RestUtils.Headers.BLOB_SIZE, 0);
    }
    ByteBuffer buffer = ByteBuffer.allocate((int) contentLength);
    for (HttpObject object : contents) {
      HttpContent content = (HttpContent) object;
      buffer.put(content.content().nioBuffer());
      ReferenceCountUtil.release(content);
    }
    return buffer;
  }

  /**
   * Discards all the content in {@code contents}.
   * @param contents the content to discard.
   * @param expectedDiscardCount the number of {@link HttpObject}s that are expected to discarded.
   */
  private void discardContent(Queue<HttpObject> contents, int expectedDiscardCount) {
    assertEquals("Objects that will be discarded are more than expected", expectedDiscardCount, contents.size());
    boolean endMarkerFound = false;
    for (HttpObject object : contents) {
      assertFalse("There should have been only a single end marker", endMarkerFound);
      endMarkerFound = object instanceof LastHttpContent;
      ReferenceCountUtil.release(object);
    }
    assertTrue("There should have been an end marker", endMarkerFound);
  }

  // BeforeClass helpers

  /**
   * Builds properties required to start a {@link RestServer} as an Ambry frontend server.
   * @return a {@link VerifiableProperties} with the parameters for an Ambry frontend server.
   */
  private static VerifiableProperties buildFrontendVProps() {
    Properties properties = new Properties();
    properties
        .put("rest.server.blob.storage.service.factory", "com.github.ambry.frontend.AmbryBlobStorageServiceFactory");
    properties.put("rest.server.router.factory", "com.github.ambry.router.InMemoryRouterFactory");
    properties.put("netty.server.port", Integer.toString(SERVER_PORT));
    return new VerifiableProperties(properties);
  }

  // postGetHeadDeleteTest() and multipartPostGetHeadTest() helpers

  /**
   * Sets headers that helps build {@link BlobProperties} on the server. See argument list for the headers that are set.
   * Any other headers have to be set explicitly.
   * @param httpHeaders the {@link HttpHeaders} where the headers should be set.
   * @param contentLength sets the {@link RestUtils.Headers#BLOB_SIZE} header. Required.
   * @param ttlInSecs sets the {@link RestUtils.Headers#TTL} header. Set to {@link Utils#Infinite_Time} if no
   *                  expiry.
   * @param isPrivate sets the {@link RestUtils.Headers#PRIVATE} header. Allowed values: true, false.
   * @param serviceId sets the {@link RestUtils.Headers#SERVICE_ID} header. Required.
   * @param contentType sets the {@link RestUtils.Headers#AMBRY_CONTENT_TYPE} header. Required and has to be a valid MIME
   *                    type.
   * @param ownerId sets the {@link RestUtils.Headers#OWNER_ID} header. Optional - if not required, send null.
   * @throws IllegalArgumentException if any of {@code headers}, {@code serviceId}, {@code contentType} is null or if
   *                                  {@code contentLength} < 0 or if {@code ttlInSecs} < -1.
   */
  private void setAmbryHeaders(HttpHeaders httpHeaders, long contentLength, long ttlInSecs, boolean isPrivate,
      String serviceId, String contentType, String ownerId) {
    if (httpHeaders != null && contentLength >= 0 && ttlInSecs >= -1 && serviceId != null && contentType != null) {
      httpHeaders.add(RestUtils.Headers.BLOB_SIZE, contentLength);
      httpHeaders.add(RestUtils.Headers.TTL, ttlInSecs);
      httpHeaders.add(RestUtils.Headers.PRIVATE, isPrivate);
      httpHeaders.add(RestUtils.Headers.SERVICE_ID, serviceId);
      httpHeaders.add(RestUtils.Headers.AMBRY_CONTENT_TYPE, contentType);
      if (ownerId != null) {
        httpHeaders.add(RestUtils.Headers.OWNER_ID, ownerId);
      }
    } else {
      throw new IllegalArgumentException("Some required arguments are null. Cannot set ambry headers");
    }
  }

  /**
   * Posts a blob with the given {@code headers} and {@code content}.
   * @param headers the headers required.
   * @param content the content of the blob.
   * @return the blob ID of the blob.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private String postBlobAndVerify(HttpHeaders headers, ByteBuffer content)
      throws ExecutionException, InterruptedException {
    FullHttpRequest httpRequest = buildRequest(HttpMethod.POST, "/", headers, content);
    Queue<HttpObject> responseParts = nettyClient.sendRequest(httpRequest, null, null).get();
    HttpResponse response = (HttpResponse) responseParts.poll();
    assertEquals("Unexpected response status", HttpResponseStatus.CREATED, response.getStatus());
    assertTrue("No Date header", HttpHeaders.getDateHeader(response, HttpHeaders.Names.DATE, null) != null);
    assertTrue("No " + RestUtils.Headers.CREATION_TIME,
        HttpHeaders.getHeader(response, RestUtils.Headers.CREATION_TIME, null) != null);
    assertEquals("Content-Length is not 0", 0, HttpHeaders.getContentLength(response));
    String blobId = HttpHeaders.getHeader(response, HttpHeaders.Names.LOCATION, null);

    if (blobId == null) {
      fail("postBlobAndVerify did not return a blob ID");
    }
    discardContent(responseParts, 1);
    return blobId;
  }

  /**
   * Gets the blob with blob ID {@code blobId} and verifies that the headers and content match with what is expected.
   * @param blobId the blob ID of the blob to GET.
   * @param expectedHeaders the expected headers in the response.
   * @param expectedContent the expected content of the blob.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void getBlobAndVerify(String blobId, HttpHeaders expectedHeaders, ByteBuffer expectedContent)
      throws ExecutionException, InterruptedException {
    FullHttpRequest httpRequest = buildRequest(HttpMethod.GET, blobId, null, null);
    Queue<HttpObject> responseParts = nettyClient.sendRequest(httpRequest, null, null).get();
    HttpResponse response = (HttpResponse) responseParts.poll();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    checkCommonGetHeadHeaders(response.headers(), expectedHeaders);
    ByteBuffer responseContent = getContent(response, responseParts);
    assertArrayEquals("GET content does not match original content", expectedContent.array(), responseContent.array());
  }

  /**
   * Gets the headers of the blob with blob ID {@code blobId} and verifies them against what is expected.
   * @param blobId the blob ID of the blob to HEAD.
   * @param expectedHeaders the expected headers in the response.
   * @param usermetadata if non-null, this is expected to come as headers prefixed with
   *                      {@link RestUtils.Headers#USER_META_DATA_OLD_STYLE_PREFIX}.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void getHeadAndVerify(String blobId, HttpHeaders expectedHeaders, ByteBuffer usermetadata)
      throws ExecutionException, InterruptedException {
    FullHttpRequest httpRequest = buildRequest(HttpMethod.HEAD, blobId, null, null);
    Queue<HttpObject> responseParts = nettyClient.sendRequest(httpRequest, null, null).get();
    HttpResponse response = (HttpResponse) responseParts.poll();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    checkCommonGetHeadHeaders(response.headers(), expectedHeaders);
    assertEquals("Content-Length does not match blob size",
        Long.parseLong(expectedHeaders.get(RestUtils.Headers.BLOB_SIZE)), HttpHeaders.getContentLength(response));
    assertEquals(RestUtils.Headers.SERVICE_ID + " does not match", expectedHeaders.get(RestUtils.Headers.SERVICE_ID),
        HttpHeaders.getHeader(response, RestUtils.Headers.SERVICE_ID));
    assertEquals(RestUtils.Headers.PRIVATE + " does not match", expectedHeaders.get(RestUtils.Headers.PRIVATE),
        HttpHeaders.getHeader(response, RestUtils.Headers.PRIVATE));
    assertEquals(RestUtils.Headers.AMBRY_CONTENT_TYPE + " does not match",
        expectedHeaders.get(RestUtils.Headers.AMBRY_CONTENT_TYPE),
        HttpHeaders.getHeader(response, RestUtils.Headers.AMBRY_CONTENT_TYPE));
    assertTrue("No " + RestUtils.Headers.CREATION_TIME,
        HttpHeaders.getHeader(response, RestUtils.Headers.CREATION_TIME, null) != null);
    if (Long.parseLong(expectedHeaders.get(RestUtils.Headers.TTL)) != Utils.Infinite_Time) {
      assertEquals(RestUtils.Headers.TTL + " does not match", expectedHeaders.get(RestUtils.Headers.TTL),
          HttpHeaders.getHeader(response, RestUtils.Headers.TTL));
    }
    if (expectedHeaders.contains(RestUtils.Headers.OWNER_ID)) {
      assertEquals(RestUtils.Headers.OWNER_ID + " does not match", expectedHeaders.get(RestUtils.Headers.OWNER_ID),
          HttpHeaders.getHeader(response, RestUtils.Headers.OWNER_ID));
    }
    verifyUserMetadataHeaders(expectedHeaders, response, usermetadata);
    discardContent(responseParts, 1);
  }

  /**
   * Verifies User metadata headers from output, to that sent in during input
   * @param expectedHeaders the expected headers in the response.
   * @param response the {@link HttpResponse} which contains the headers of the response.
   * @param usermetadata if non-null, this is expected to come as headers prefixed with
   *                      {@link RestUtils.Headers#USER_META_DATA_OLD_STYLE_PREFIX}.
   */
  private void verifyUserMetadataHeaders(HttpHeaders expectedHeaders, HttpResponse response, ByteBuffer usermetadata) {
    if (usermetadata == null) {
      for (Map.Entry<String, String> header : expectedHeaders) {
        String key = header.getKey();
        if (key.startsWith(RestUtils.Headers.USER_META_DATA_HEADER_PREFIX)) {
          assertEquals("Value for " + key + " does not match in user metadata", header.getValue(),
              HttpHeaders.getHeader(response, key));
        }
      }
      for (Map.Entry<String, String> header : response.headers()) {
        String key = header.getKey();
        if (key.startsWith(RestUtils.Headers.USER_META_DATA_HEADER_PREFIX)) {
          assertTrue("Key " + key + " does not exist in expected headers", expectedHeaders.contains(key));
        }
      }
    } else {
      int highestIndex = -1;
      for (Map.Entry<String, String> header : response.headers()) {
        String key = header.getKey();
        if (key.startsWith(RestUtils.Headers.USER_META_DATA_OLD_STYLE_PREFIX)) {
          int index = Integer.parseInt(key.substring(RestUtils.Headers.USER_META_DATA_OLD_STYLE_PREFIX.length()));
          highestIndex = Math.max(index, highestIndex);
        }
      }
      if (highestIndex == -1) {
        fail("There should have been user metadata in the response");
      } else {
        for (int i = 0; i <= highestIndex; i++) {
          String value = response.headers().get(RestUtils.Headers.USER_META_DATA_OLD_STYLE_PREFIX + i);
          for (byte b : value.getBytes()) {
            assertTrue("Received user metadata should not be longer than the original", usermetadata.hasRemaining());
            assertEquals("Byte of received user metadata does not match original", usermetadata.get(), b);
          }
        }
        assertFalse("Some user metadata was not found in the response", usermetadata.hasRemaining());
      }
    }
  }

  /**
   * Deletes the blob with blob ID {@code blobId} and verifies the response returned.
   * @param blobId the blob ID of the blob to DELETE.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void deleteBlobAndVerify(String blobId)
      throws ExecutionException, InterruptedException {
    FullHttpRequest httpRequest = buildRequest(HttpMethod.DELETE, blobId, null, null);
    verifyDeleted(httpRequest, HttpResponseStatus.ACCEPTED);
  }

  /**
   * Verifies that the right response code is returned for GET, HEAD and DELETE once a blob is deleted.
   * @param blobId the ID of the blob that was deleted.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void verifyOperationsAfterDelete(String blobId)
      throws ExecutionException, InterruptedException {
    FullHttpRequest httpRequest = buildRequest(HttpMethod.GET, blobId, null, null);
    verifyDeleted(httpRequest, HttpResponseStatus.GONE);

    httpRequest = buildRequest(HttpMethod.HEAD, blobId, null, null);
    verifyDeleted(httpRequest, HttpResponseStatus.GONE);

    httpRequest = buildRequest(HttpMethod.DELETE, blobId, null, null);
    verifyDeleted(httpRequest, HttpResponseStatus.ACCEPTED);
  }

  /**
   * Verifies that a request returns the right response code  once the blob has been deleted.
   * @param httpRequest the {@link FullHttpRequest} to send to the server.
   * @param expectedStatusCode the expected {@link HttpResponseStatus}.
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private void verifyDeleted(FullHttpRequest httpRequest, HttpResponseStatus expectedStatusCode)
      throws ExecutionException, InterruptedException {
    Queue<HttpObject> responseParts = nettyClient.sendRequest(httpRequest, null, null).get();
    HttpResponse response = (HttpResponse) responseParts.poll();
    assertEquals("Unexpected response status", expectedStatusCode, response.getStatus());
    assertTrue("No Date header", HttpHeaders.getDateHeader(response, HttpHeaders.Names.DATE, null) != null);
    discardContent(responseParts, 1);
  }

  /**
   * Checks headers that are common to HEAD and GET.
   * @param receivedHeaders the {@link HttpHeaders} that were received.
   * @param expectedHeaders the expected headers.
   */
  private void checkCommonGetHeadHeaders(HttpHeaders receivedHeaders, HttpHeaders expectedHeaders) {
    assertEquals("Content-Type does not match", expectedHeaders.get(RestUtils.Headers.AMBRY_CONTENT_TYPE),
        receivedHeaders.get(HttpHeaders.Names.CONTENT_TYPE));
    assertTrue("No Date header", receivedHeaders.get(HttpHeaders.Names.DATE) != null);
    assertTrue("No Last-Modified header", receivedHeaders.get(HttpHeaders.Names.LAST_MODIFIED) != null);
    assertEquals(RestUtils.Headers.BLOB_SIZE + " does not match", expectedHeaders.get(RestUtils.Headers.BLOB_SIZE),
        receivedHeaders.get(RestUtils.Headers.BLOB_SIZE));
  }

  /**
   * Posts a blob with the given {@code headers} and {@code content}.
   * @param headers the headers required.
   * @param content the content of the blob.
   * @param usermetadata the {@link ByteBuffer} that represents user metadata
   * @return the blob ID of the blob.
   * @throws Exception
   */
  private String multipartPostBlobAndVerify(HttpHeaders headers, ByteBuffer content, ByteBuffer usermetadata)
      throws Exception {
    HttpRequest httpRequest = RestTestUtils.createRequest(HttpMethod.POST, "/", headers);
    HttpPostRequestEncoder encoder = createEncoder(httpRequest, content, usermetadata);
    Queue<HttpObject> responseParts = nettyClient.sendRequest(encoder.finalizeRequest(), encoder, null).get();
    HttpResponse response = (HttpResponse) responseParts.poll();
    assertEquals("Unexpected response status", HttpResponseStatus.CREATED, response.getStatus());
    assertTrue("No Date header", HttpHeaders.getDateHeader(response, HttpHeaders.Names.DATE, null) != null);
    assertTrue("No " + RestUtils.Headers.CREATION_TIME,
        HttpHeaders.getHeader(response, RestUtils.Headers.CREATION_TIME, null) != null);
    assertEquals("Content-Length is not 0", 0, HttpHeaders.getContentLength(response));
    String blobId = HttpHeaders.getHeader(response, HttpHeaders.Names.LOCATION, null);

    if (blobId == null) {
      fail("postBlobAndVerify did not return a blob ID");
    }
    discardContent(responseParts, 1);
    return blobId;
  }

  /**
   * Creates a {@link HttpPostRequestEncoder} that encodes the given {@code request} and {@code blobContent}.
   * @param request the {@link HttpRequest} containing headers and other metadata about the request.
   * @param blobContent the {@link ByteBuffer} that represents the content of the blob.
   * @param usermetadata the {@link ByteBuffer} that represents user metadata
   * @return a {@link HttpPostRequestEncoder} that can encode the {@code request} and {@code blobContent}.
   * @throws HttpPostRequestEncoder.ErrorDataEncoderException
   * @throws IOException
   */
  private HttpPostRequestEncoder createEncoder(HttpRequest request, ByteBuffer blobContent, ByteBuffer usermetadata)
      throws HttpPostRequestEncoder.ErrorDataEncoderException, IOException {
    HttpDataFactory httpDataFactory = new DefaultHttpDataFactory(false);
    HttpPostRequestEncoder encoder = new HttpPostRequestEncoder(httpDataFactory, request, true);
    FileUpload fileUpload = new MemoryFileUpload(RestUtils.MultipartPost.BLOB_PART, RestUtils.MultipartPost.BLOB_PART,
        "application/octet-stream", "", Charset.forName("UTF-8"), blobContent.remaining());
    fileUpload.setContent(Unpooled.wrappedBuffer(blobContent));
    encoder.addBodyHttpData(fileUpload);
    fileUpload =
        new MemoryFileUpload(RestUtils.MultipartPost.USER_METADATA_PART, RestUtils.MultipartPost.USER_METADATA_PART,
            "application/octet-stream", "", Charset.forName("UTF-8"), usermetadata.remaining());
    fileUpload.setContent(Unpooled.wrappedBuffer(usermetadata));
    encoder.addBodyHttpData(fileUpload);
    return encoder;
  }
}