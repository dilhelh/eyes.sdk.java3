package com.applitools.eyes.visualgrid.model;

import com.applitools.connectivity.ServerConnector;
import com.applitools.connectivity.TestServerConnector;
import com.applitools.connectivity.api.AsyncRequest;
import com.applitools.connectivity.api.AsyncRequestCallback;
import com.applitools.connectivity.api.Response;
import com.applitools.eyes.Logger;
import com.applitools.eyes.RectangleSize;
import com.applitools.eyes.TaskListener;
import com.applitools.eyes.UserAgent;
import com.applitools.eyes.fluent.CheckSettings;
import com.applitools.eyes.selenium.BrowserType;
import com.applitools.eyes.utils.ReportingTestSuite;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridRunner;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.GeneralUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestRenderingTask extends ReportingTestSuite {

    public TestRenderingTask() {
        super.setGroupName("core");
    }

    @Test
    public void testAsyncDownloadResources() throws Exception {
        final ExecutorService service = Executors.newCachedThreadPool();

        // Get a json of uris simulating the real resource uris structure
        File file = new File(Objects.requireNonNull(getClass().getClassLoader().getResource("resource_urls.json")).getFile());
        String jsonString = GeneralUtils.readToEnd(new FileInputStream(file));
        ObjectMapper jsonMapper = new ObjectMapper();
        final Map<String, Map> urls = jsonMapper.readValue(jsonString, Map.class);

        // Arguments for fetchAllResources
        Set<URI> resourceUrls = stringsToUris(urls.keySet());
        final Map<String, RGridResource> allBlobs = new HashMap<>();
        final FrameData frameData = mock(FrameData.class);
        when(frameData.getUrl()).thenReturn("");

        // Mocking for RenderingTask
        final Future<?> future = mock(Future.class);
        when(future.get()).thenThrow(new IllegalStateException());
        when(future.get(anyLong(), (TimeUnit) any())).thenThrow(new IllegalStateException());
        UserAgent userAgent = mock(UserAgent.class);
        when(userAgent.getOriginalUserAgentString()).thenReturn("");

        final AtomicInteger counter = new AtomicInteger();
        ServerConnector serverConnector = mock(ServerConnector.class);

        final DomAnalyzer domAnalyzer = new DomAnalyzer(new Logger(), serverConnector, new NullDebugResourceWriter(),
                frameData, new HashMap<String, RGridResource>(), userAgent);

        RGridResource cachedResource = mock(RGridResource.class);
        when(cachedResource.getUrl()).thenReturn("12");
        when(cachedResource.getContentType()).thenReturn("");
        when(cachedResource.parse(ArgumentMatchers.<Logger>any(), anyString()))
                .thenReturn(stringsToUris(getInnerMap(urls, "12").keySet()));
        domAnalyzer.fetchedCacheMap.put("12", cachedResource);

        // When RenderingTask tries to get a new resource, this task will be submitted to the ExecutorService
        when(serverConnector.downloadResource(ArgumentMatchers.<URI>any(), anyString(), anyString(), ArgumentMatchers.<TaskListener<RGridResource>>any()))
                .thenAnswer(new Answer<Future<?>>() {
            @Override
            public Future<?> answer(final InvocationOnMock invocationOnMock) throws Throwable {
                try {
                    service.submit(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (counter) {
                                try {
                                    // Sleeping so the async tasks will take some time to finish
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }

                                counter.getAndIncrement();
                                URI url = invocationOnMock.getArgument(0);
                                Map innerUrls = TestRenderingTask.this.getInnerMap(urls, url.toString());

                                RGridResource resource = mock(RGridResource.class);
                                when(resource.getUrl()).thenReturn(url.toString());
                                when(resource.getContentType()).thenReturn("");
                                try {
                                    when(resource.parse(ArgumentMatchers.<Logger>any(), anyString()))
                                            .thenReturn(stringsToUris(innerUrls.keySet()));
                                } catch (URISyntaxException e) {
                                    throw new IllegalStateException(e);
                                }

                                TaskListener<RGridResource> listener = invocationOnMock.getArgument(3);
                                listener.onComplete(resource);
                            }
                        }
                    });
                    return future;
                } catch (Exception e) {
                    throw new Throwable(e);
                }
            }
        });

        // We call the method which activates the process of collecting resources and wait to see if it ends properly.
        domAnalyzer.fetchAllResources(allBlobs, resourceUrls);
        domAnalyzer.resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
        Assert.assertEquals(counter.get(), 7);
    }

    @Test
    public void testPutResources() throws Exception {
        final ServerConnector serverConnector = TestServerConnector.getOfflineServerConnector(null,
                new AsyncRequest(new Logger()) {
                    @Override
                    public AsyncRequest header(String name, String value) {
                        return this;
                    }

                    @Override
                    public Future<?> method(String method, AsyncRequestCallback callback, Object data, String contentType, boolean logIfError) {
                        callback.onComplete(new Response(new Logger()) {
                            @Override
                            public int getStatusCode() {
                                return HttpStatus.SC_OK;
                            }

                            @Override
                            public String getStatusPhrase() {
                                return "";
                            }

                            @Override
                            public String getHeader(String name, boolean ignoreCase) {
                                return "";
                            }

                            @Override
                            public String getBodyString() {
                                return "content";
                            }

                            @Override
                            protected void readEntity() {}


                            @Override
                            public void close() {}
                        });
                        return null;
                    }
                });
        RenderingInfo renderingInfo = new RenderingInfo("", "", "", "", 0, 0);
        serverConnector.setRenderingInfo(renderingInfo);

        final Future<?> future = mock(Future.class);
        when(future.get()).thenThrow(new IllegalStateException());
        when(future.get(anyLong(), (TimeUnit) any())).thenThrow(new IllegalStateException());
        VisualGridTask visualGridTask = mock(VisualGridTask.class);
        IEyesConnector eyesConnector = mock(IEyesConnector.class);
        when(visualGridTask.getEyesConnector()).thenReturn(eyesConnector);
        UserAgent userAgent = mock(UserAgent.class);
        when(userAgent.getOriginalUserAgentString()).thenReturn("");
        RenderRequest renderRequest = mock(RenderRequest.class);
        final RenderingTask renderingTask = new RenderingTask(eyesConnector, renderRequest, visualGridTask,
                new VisualGridRunner(10), null, userAgent);

        when(eyesConnector.renderPutResource(any(RunningRender.class), any(RGridResource.class), anyString(), ArgumentMatchers.<TaskListener<Boolean>>any()))
                .thenAnswer(new Answer<Future<?>>() {
                    @Override
                    public Future<?> answer(InvocationOnMock invocation) throws Throwable {
                        serverConnector.renderPutResource(
                                (RunningRender) invocation.getArgument(0),
                                (RGridResource) invocation.getArgument(1),
                                (String) invocation.getArgument(2),
                                (TaskListener<Boolean>) invocation.getArgument(3));
                        return future;
                    }
                });


        RunningRender runningRender = new RunningRender();
        runningRender.setRenderId("");
        runningRender.setNeedMoreResources(Arrays.asList("1", "2", "3"));
        Map<String, RGridResource> resourceMap = new HashMap<>();
        resourceMap.put("1", new RGridResource("1", "", "1".getBytes()));
        resourceMap.put("2", new RGridResource("2", "", "2".getBytes()));
        resourceMap.put("3", new RGridResource("3", "", "3".getBytes()));
        resourceMap.put("4", new RGridResource("4", "", "4".getBytes()));
        when(renderRequest.getResources()).thenReturn(resourceMap);
        renderingTask.uploadResources(Collections.singletonList(runningRender), false);
        renderingTask.resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
    }

    @Test
    public void testResourcesCaching() {
        VisualGridTask visualGridTask = mock(VisualGridTask.class);
        IEyesConnector eyesConnector = mock(IEyesConnector.class);
        when(visualGridTask.getEyesConnector()).thenReturn(eyesConnector);
        RenderBrowserInfo browserInfo = new RenderBrowserInfo(new RectangleSize(800, 800), BrowserType.CHROME);
        when(visualGridTask.getBrowserInfo()).thenReturn(browserInfo);
        UserAgent userAgent = mock(UserAgent.class);
        when(userAgent.getOriginalUserAgentString()).thenReturn("");
        CheckSettings checkSettings = mock(CheckSettings.class);
        when(checkSettings.getSizeMode()).thenReturn("viewport");
        when(checkSettings.isStitchContent()).thenReturn(true);

        List<String> urls = Arrays.asList("http://1.com", "http://2.com", "http://3.com");
        FrameData frameData = new FrameData();
        frameData.setUrl("http://random.com");
        frameData.setResourceUrls(urls);
        frameData.setBlobs(new ArrayList<BlobData>());
        frameData.setFrames(new ArrayList<FrameData>());
        frameData.setCdt(new ArrayList<CdtData>());
        frameData.setSrcAttr("");

        final AtomicReference<List<RenderingTask>> reference = new AtomicReference<>();
        ResourceCollectionTask resourceCollectionTask = new ResourceCollectionTask(eyesConnector,
                Collections.singletonList(visualGridTask), frameData, userAgent, checkSettings, new TaskListener<List<RenderingTask>>() {
            @Override
            public void onComplete(List<RenderingTask> renderingTasks) {
                reference.set(renderingTasks);
            }

            @Override
            public void onFail() {

            }
        });

        for (String url : urls) {
            resourceCollectionTask.fetchedCacheMap.put(url, RGridResource.createEmpty(url));
        }

        resourceCollectionTask.call();

        Map<String, RGridResource> resourceMap = reference.get().get(0).renderRequests.get(0).getResources();
        Assert.assertEquals(resourceMap.keySet(), new HashSet<>(urls));
    }

    /**
     * This method searches recursively for a key in a map and returns its value
     */
    private Map getInnerMap(Map<String, Map> outerMap, String key) {
        if (outerMap.containsKey(key)) {
            return outerMap.get(key);
        }

        for (String k : outerMap.keySet()) {
            if (outerMap.get(k).isEmpty()) {
                continue;
            }

            Map result = getInnerMap(outerMap.get(k), key);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * This method converts a collection of string uris to a collection of URIs
     */
    private Set<URI> stringsToUris(Set<String> strUris) throws URISyntaxException {
        Set<URI> uris = new HashSet<>();
        for (String url : strUris) {
            uris.add(new URI(url));
        }

        return uris;
    }
}
