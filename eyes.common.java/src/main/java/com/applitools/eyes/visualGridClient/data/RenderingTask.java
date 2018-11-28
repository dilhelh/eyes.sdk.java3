package com.applitools.eyes.visualGridClient.data;

import com.applitools.eyes.IDownloadListener;
import com.applitools.eyes.visualGridClient.IEyesConnector;
import com.applitools.eyes.visualGridClient.IResourceFuture;
import com.applitools.utils.GeneralUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.ArrayUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderingTask implements Callable<RenderStatusResults> {


    private static AtomicBoolean isThrown = new AtomicBoolean(false);

    private IEyesConnector eyesConnector;
    private String script;
    private RenderingConfiguration.checkRGSettings renderingConfiguration;
    private List<RunningTest> testList;
    private RenderingInfo renderingInfo;
    private RenderingTaskListener runningTestListener;
    private Map<String, IResourceFuture> cacheMap;

    interface RenderingTaskListener {
        void onTaskComplete(RenderingTask task);
    }

    public RenderingTask(IEyesConnector eyesConnector, String script, RenderingConfiguration.checkRGSettings renderingConfiguration, List<RunningTest> testList, RenderingInfo renderingInfo, RenderingTaskListener runningTestListener, Map<String, IResourceFuture> cacheMap) {
        this.eyesConnector = eyesConnector;
        this.script = script;
        this.renderingConfiguration = renderingConfiguration;
        this.testList = testList;
        this.renderingInfo = renderingInfo;
        this.runningTestListener = runningTestListener;
        this.cacheMap = cacheMap;
    }

    @Override
    public RenderStatusResults call(){
        HashMap<String, Object> result;
        List<RenderRequest> requests = null;
        Map<RunningTest, RenderRequest> testToRenderRequestMapping = new HashMap<>();
        try {

            //Parse to JSON
            result = GeneralUtils.parseJsonToObject(script);

            //Build RenderRequests
            requests = prepareDataForRG(result, renderingConfiguration);

        } catch (IOException e) {
            e.printStackTrace();
        }
        matchRequestsToTests(requests, testToRenderRequestMapping);

        sendResourcesToRG();

        return null;
    }

    private void matchRequestsToTests(List<RenderRequest> requests, Map<RunningTest, RenderRequest> testToRenderRequestMapping) {
        for (RunningTest runningTest : testList) {
            for (RenderRequest request : requests) {

                RenderingConfiguration.RenderBrowserInfo browserInfo = runningTest.getBrowserInfo();
                RenderInfo renderInfo = request.getRenderInfo();

                boolean isSameBrowser = request.getBrowserName().equalsIgnoreCase(browserInfo.getBrowserType());
                boolean isSameViewport = renderInfo.getHeight() == browserInfo.getHeight() && renderInfo.getWidth() == browserInfo.getWidth();

                if(isSameBrowser && isSameViewport){
                    testToRenderRequestMapping.put(runningTest, request);
                }
            }

        }
    }

    private void sendResourcesToRG() {
    }

    private static boolean isThrown() {
        return RenderingTask.isThrown.get();
    }

    private List<RenderRequest> prepareDataForRG(HashMap<String, Object> result, RenderingConfiguration.checkRGSettings settings) {

        final List<RGridResource> allBlobs = Collections.synchronizedList(new ArrayList<RGridResource>());
        List<String> resourceUrls = null;

        org.apache.commons.codec.binary.Base64 codec = new Base64();

        for (String key : result.keySet()) {
            Object value = result.get(key);
            switch (key) {
                case "blobs":
                    List listOfBlobs = (List) value;
                    for (Object blob : listOfBlobs) {
                        Map blobAsMap = (Map) blob;
                        String contentAsString = (String) blobAsMap.get("value");
                        Byte[] content = ArrayUtils.toObject(codec.decode(contentAsString));
                        RGridResource resource = new RGridResource((String) blobAsMap.get("url"), (String) blobAsMap.get("type"), content);
                        allBlobs.add(resource);
                    }
                    break;
                case "resourceUrls":

                    List<String> list = (List<String>) value;
//                    list.add("https://nikita-andreev.github.io/applitools/style0.css");
                    resourceUrls = Collections.synchronizedList(list);
                    break;
            }
        }

        //Fetch all resources
        fetchAllResources(allBlobs, resourceUrls);

        //Create RenderingRequest
        List<RenderRequest> allRequestsForRG = buildRenderRequests(result, settings, allBlobs);


        return allRequestsForRG;
    }

    private List<RenderRequest> buildRenderRequests(HashMap<String, Object> result, RenderingConfiguration.checkRGSettings settings, List<RGridResource> allBlobs) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        String cdt;
        RGridDom dom = null;
        Map<String, RGridResource> resourceMapping = new HashMap<>();
        try {
            cdt = objectMapper.writeValueAsString(result.get("cdt"));
            dom = new RGridDom();
            dom.setCdt(cdt);
            for (RGridResource blob : allBlobs) {
                resourceMapping.put(blob.getUrl(), blob);
            }
        } catch (JsonProcessingException e) {
            GeneralUtils.logExceptionStackTrace(e);
        }
        dom.setResources(resourceMapping);
        //Create RG requests
        List<RenderRequest> allRequestsForRG = new ArrayList<>();

        double randomRequestId = Math.random();

        for (RunningTest runningTest : this.testList) {

            RenderingConfiguration.RenderBrowserInfo browserInfo = runningTest.getBrowserInfo();
            RenderInfo renderInfo = new RenderInfo(browserInfo.getWidth(), browserInfo.getHeight(), browserInfo.getSizeMode(), settings.getRegion(), browserInfo.getEmulationInfo());

            RenderRequest request = new RenderRequest(randomRequestId, this.renderingInfo.getResultsUrl(), (String)result.get("url") ,dom ,
                    resourceMapping , renderInfo, browserInfo.getPlatform(), browserInfo.getBrowserType(), settings.getScriptHooks(), null, settings.isSendDom());

            try {
                String value = objectMapper.writeValueAsString(request);
                System.out.println(value);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            allRequestsForRG.add(request);
        }
        return allRequestsForRG;
    }

    private void fetchAllResources(final List<RGridResource> allBlobs, List<String> resourceUrls) {

        //Filter out the urls found in cacheMap
        List<String> urlsFoundInCache = new ArrayList<>();
        Iterator<String> iterator = resourceUrls.iterator();
        while (iterator.hasNext()) {
            String next =  iterator.next();
            if(cacheMap.containsKey(next)){
                urlsFoundInCache.add(next);
                iterator.remove();
            }
        }

        final Phaser phaser = new Phaser(1);
        for (String link : resourceUrls) {
            IEyesConnector eyesConnector = this.testList.get(0).getEyes();
            URL url = null;
            try {
                url = new URL(link);
                final URL finalUrl = url;
                phaser.register();
                IResourceFuture future = eyesConnector.getResource(url, new IDownloadListener<Byte[]>() {
                    @Override
                    public void onDownloadComplete(Byte[] downloadedString, String contentType) {
                        RGridResource gridResource = new RGridResource(finalUrl.toString(), contentType, downloadedString);
                        allBlobs.add(gridResource);
                        phaser.arriveAndDeregister();
                    }

                    @Override
                    public void onDownloadFailed() {
                    }
                });
                this.cacheMap.put(url.toString(), future);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        //Wait for all results
        phaser.arriveAndAwaitAdvance();

        for (String url : urlsFoundInCache) {
            IResourceFuture future = cacheMap.get(url);
            RGridResource resource = null;
            try {
                resource = future.get();
            } catch (InterruptedException | ExecutionException e) {
                GeneralUtils.logExceptionStackTrace(e);
            }
            allBlobs.add(resource);
        }
    }

}

