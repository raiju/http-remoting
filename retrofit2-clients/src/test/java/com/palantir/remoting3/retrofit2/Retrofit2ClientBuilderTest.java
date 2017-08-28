/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.remoting3.retrofit2;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public final class Retrofit2ClientBuilderTest extends TestBase {

    @Rule
    public final MockWebServer server = new MockWebServer();

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testRelativeRetrofitEndPoints_workWithArbitraryBaseUrlFormats() throws Exception {
        assertRequestUrlYieldsHttpPath("/api/", "/api/%s");
        assertRequestUrlYieldsHttpPath("/api", "/api/%s");
        assertRequestUrlYieldsHttpPath("api/", "/api/%s");
        assertRequestUrlYieldsHttpPath("api", "/api/%s");
        assertRequestUrlYieldsHttpPath("/", "/%s");
        assertRequestUrlYieldsHttpPath("", "/%s");
    }

    private void assertRequestUrlYieldsHttpPath(String basePath, String expectedQueryPath) throws Exception {
        HttpUrl url = server.url(basePath);
        TestService service = Retrofit2Client.create(TestService.class, "agent", createTestConfig(url.toString()));

        server.enqueue(new MockResponse().setBody("\"server\""));
        assertThat(service.get().execute().body(), is("server"));
        assertThat(server.takeRequest().getPath(), is(String.format(expectedQueryPath, "")));

        server.enqueue(new MockResponse().setBody("\"server\""));
        assertThat(service.getRelative().execute().body(), is("server"));
        assertThat(server.takeRequest().getPath(), is(String.format(expectedQueryPath, "relative")));
    }

    // See comment regarding Retrofit2 URL handling on TestService#getAbsolute()
    @Test
    public void testAbsoluteRetrofitEndpoints_failRetryInterceptor() throws Exception {
        for (String basePath : ImmutableList.of("/api/", "/api", "api/", "api")) {
            HttpUrl url = server.url(basePath);
            TestService service = Retrofit2Client.create(TestService.class, "agent", createTestConfig(url.toString()));
            try {
                service.getAbsolute().execute();
                fail();
            } catch (IllegalStateException e) {
                assertThat(e.getMessage(), startsWith(
                        "Unrecognized server URI in the request http://localhost:" + server.getPort() + "/absolute."));
            }
        }
    }

    @Test
    public void testCaseInsensitiveHostNames_workWithEquivalentUrls() throws Exception {
        TestService service = Retrofit2Client.create(
                TestService.class, "agent", createTestConfig(
                        String.format("http://%s:%s/api/", server.getHostName().toUpperCase(), server.getPort())
                ));
        server.enqueue(new MockResponse().setBody("\"server\""));
        service.getRelative().execute();
    }

    @Test
    public void testCaseSensitivePathNames_doesNotWorkWithNonEquivalentUrls() throws Exception {
        TestService service = Retrofit2Client.create(
                TestService.class, "agent", createTestConfig(
                        String.format("http://%s:%s/api/", server.getHostName().toUpperCase(), server.getPort())
                ));
        try {
            service.getAbsoluteApiTitleCase().execute();
            fail();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), startsWith(
                    "Unrecognized server URI in the request http://localhost:" + server.getPort() + "/Api."));
        }
    }

    @Test
    public void testRetrofit2ClientWithMultiServerRetryInterceptorRedirectToAvailableServer() throws IOException {
        MockWebServer otherServer = new MockWebServer();
        TestService service = Retrofit2Client.create(
                TestService.class, "agent", createTestConfig(
                        String.format("http://%s:%s/api/", server.getHostName().toUpperCase(), server.getPort()),
                        String.format(
                                "http://%s:%s/api/", otherServer.getHostName().toUpperCase(), otherServer.getPort()))
        );

        otherServer.enqueue(new MockResponse().setBody("\"pong\""));
        assertThat(service.get().execute().body(), is("pong"));
    }

    @Test
    public void testUserAgent_defaultHeaderIsSent() throws InterruptedException, IOException {
        String userAgent = "TestSuite/1 (0.0.0)";
        TestService service = Retrofit2Client.create(
                TestService.class, userAgent, createTestConfig(
                        String.format("http://%s:%s/api/", server.getHostName().toUpperCase(), server.getPort())));
        server.enqueue(new MockResponse().setBody("\"server\""));
        service.get().execute();

        RecordedRequest capturedRequest = server.takeRequest();
        assertThat(capturedRequest.getHeader("User-Agent"), startsWith(userAgent));
    }

    @Test
    public void testUserAgent_invalidUserAgentThrows() throws InterruptedException {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage(is("User Agent must match pattern '[A-Za-z0-9()\\-#;/.,_\\s]+': !@"));
        Retrofit2Client.create(TestService.class, "!@", createTestConfig("http://localhost:" + server.getPort()));
    }
}