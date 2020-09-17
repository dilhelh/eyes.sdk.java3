package com.applitools.eyes.visualgrid.model;

import com.applitools.ICheckSettings;
import com.applitools.ICheckSettingsInternal;
import com.applitools.eyes.*;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridRunner;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.ArgumentGuard;
import com.applitools.utils.EyesSyncObject;
import com.applitools.utils.GeneralUtils;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class ResourceCollectionTask implements Callable<TestResultContainer> {

    public static final String FULLPAGE = "full-page";
    public static final String VIEWPORT = "viewport";

    private final Logger logger;
    private final VisualGridRunner runner;
    private final IEyesConnector eyesConnector;
    private final FrameData domData;
    private final UserAgent userAgent;
    private final RenderingInfo renderingInfo;
    private final List<VisualGridSelector[]> regionSelectors;
    private final ICheckSettings checkSettings;
    private final List<VisualGridTask> checkTasks;
    final Map<String, RGridResource> fetchedCacheMap;
    final Set<String> uploadedResourcesCache;
    private IDebugResourceWriter debugResourceWriter;
    private final TaskListener<List<RenderingTask>> listener;
    private RenderingTask.RenderTaskListener renderTaskListener;

    Phaser phaser = new Phaser();

    // Listener for putResource tasks
    final TaskListener<Boolean> putListener = new TaskListener<Boolean>() {
        @Override
        public void onComplete(Boolean isSucceeded) {
            try {
                if (!isSucceeded) {
                    logger.log("Failed putting resource");
                }
            } finally {
                phaser.arriveAndDeregister();
            }
        }

        @Override
        public void onFail() {
            phaser.arriveAndDeregister();
            logger.log("Failed putting resource");
        }
    };

    /**
     * For tests only
     */
    public ResourceCollectionTask(IEyesConnector eyesConnector, List<VisualGridTask> checkTasks, FrameData domData,
                                  UserAgent userAgent, ICheckSettings checkSettings, TaskListener<List<RenderingTask>> listTaskListener) {
        this.eyesConnector = eyesConnector;
        this.checkTasks = checkTasks;
        this.domData = domData;
        this.userAgent = userAgent;
        this.checkSettings = checkSettings;
        this.listener = listTaskListener;
        this.fetchedCacheMap = new HashMap<>();
        this.uploadedResourcesCache = new HashSet<>();
        this.logger = new Logger();
        this.regionSelectors = new ArrayList<>();
        this.renderingInfo = new RenderingInfo();
        this.runner = new VisualGridRunner(10);
    }

    public ResourceCollectionTask(VisualGridRunner runner, IEyesConnector eyesConnector, FrameData domData,
                                  UserAgent userAgent, List<VisualGridSelector[]> regionSelectors,
                                  ICheckSettings checkSettings, List<VisualGridTask> checkTasks,
                                  IDebugResourceWriter debugResourceWriter, TaskListener<List<RenderingTask>> listener,
                                  RenderingTask.RenderTaskListener renderTaskListener) {
        ArgumentGuard.notNull(checkTasks, "checkTasks");
        ArgumentGuard.notEqual(checkTasks.size(), 0, "checkTasks");
        this.logger = runner.getLogger();
        this.runner = runner;
        this.eyesConnector = eyesConnector;
        this.fetchedCacheMap = runner.getCachedResources();
        this.uploadedResourcesCache = runner.getCheckResourceCache();
        this.domData = domData;
        this.userAgent = userAgent;
        this.checkSettings = checkSettings;
        this.checkTasks = checkTasks;
        this.renderingInfo = runner.getRenderingInfo();
        this.regionSelectors = regionSelectors;
        this.debugResourceWriter = debugResourceWriter;
        this.listener = listener;
        this.renderTaskListener = renderTaskListener;
    }

    @Override
    public TestResultContainer call() {
        try {
            DomAnalyzer domAnalyzer = new DomAnalyzer(logger, eyesConnector.getServerConnector(),
                    debugResourceWriter, domData, fetchedCacheMap, userAgent);
            Map<String, RGridResource> resourceMap = domAnalyzer.analyze();
            List<RenderRequest> renderRequests = buildRenderRequests(domData, resourceMap);
            if (debugResourceWriter != null && !(debugResourceWriter instanceof NullDebugResourceWriter)) {
                for (RenderRequest renderRequest : renderRequests) {
                    try {
                        debugResourceWriter.write(renderRequest.getDom().asResource());
                    } catch (JsonProcessingException e) {
                        GeneralUtils.logExceptionStackTrace(logger, e);
                    }
                    for (RGridResource value : renderRequest.getResources().values()) {
                        this.debugResourceWriter.write(value);
                    }
                }
            }

            logger.verbose("exit - returning renderRequest array of length: " + renderRequests.size());
            List<RenderingTask> renderingTasks = new ArrayList<>();
            for (int i = 0; i < renderRequests.size(); i++) {
                VisualGridTask checkTask = checkTasks.get(i);
                checkTask.setReadyForRender();
                renderingTasks.add(new RenderingTask(eyesConnector, renderRequests.get(i), checkTask, runner, renderTaskListener));
            }

            logger.verbose("Uploading missing resources");
            phaser = new Phaser();
            Map<String, RGridResource> missingResources = checkResourcesStatus(renderRequests.get(0).getDom(), resourceMap);
            uploadResources(missingResources);
            try {
                if (phaser.getRegisteredParties() > 0) {
                    phaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException | TimeoutException e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
                phaser.forceTermination();
            }

            listener.onComplete(renderingTasks);
        } catch (Throwable t) {
            GeneralUtils.logExceptionStackTrace(logger, t);
            listener.onFail();
        }

        return null;
    }

    private List<RenderRequest> buildRenderRequests(FrameData result, Map<String, RGridResource> resourceMapping) throws JsonProcessingException {

        RGridDom dom = new RGridDom(result.getCdt(), resourceMapping, result.getUrl(), logger, "buildRenderRequests");
        fetchedCacheMap.put(result.getUrl(), dom.asResource());

        //Create RG requests
        List<RenderRequest> allRequestsForRG = new ArrayList<>();
        ICheckSettingsInternal checkSettingsInternal = (ICheckSettingsInternal) this.checkSettings;


        List<VisualGridSelector> regionSelectorsList = new ArrayList<>();

        for (VisualGridSelector[] regionSelector : this.regionSelectors) {
            regionSelectorsList.addAll(Arrays.asList(regionSelector));
        }

        logger.verbose("region selectors count: " + regionSelectorsList.size());
        logger.verbose("check visual grid tasks count: " + this.checkTasks.size());

        for (VisualGridTask visualGridTask : this.checkTasks) {

            RenderBrowserInfo browserInfo = visualGridTask.getBrowserInfo();

            String sizeMode = checkSettingsInternal.getSizeMode();

            if (sizeMode.equalsIgnoreCase(VIEWPORT) && checkSettingsInternal.isStitchContent()) {
                sizeMode = FULLPAGE;
            }

            RenderInfo renderInfo = new RenderInfo(browserInfo.getWidth(), browserInfo.getHeight(),
                    sizeMode, checkSettingsInternal.getTargetRegion(), checkSettingsInternal.getVGTargetSelector(),
                    browserInfo.getEmulationInfo(), browserInfo.getIosDeviceInfo());

            RenderRequest request = new RenderRequest(this.renderingInfo.getResultsUrl(), result.getUrl(), dom,
                    resourceMapping, renderInfo, browserInfo.getPlatform(), browserInfo.getBrowserType(),
                    checkSettingsInternal.getScriptHooks(), regionSelectorsList, checkSettingsInternal.isSendDom(),
                    visualGridTask, this.renderingInfo.getStitchingServiceUrl(), checkSettingsInternal.getVisualGridOptions());

            allRequestsForRG.add(request);
        }

        logger.verbose("count of all requests for RG: " + allRequestsForRG.size());
        return allRequestsForRG;
    }

    /**
     * Checks with the server what resources are missing.
     * @return All the missing resources to upload.
     */
    Map<String, RGridResource> checkResourcesStatus(RGridDom dom, Map<String, RGridResource> resourceMap) throws JsonProcessingException {
        List<HashObject> hashesToCheck = new ArrayList<>();
        Map<String, String> hashToResourceUrl = new HashMap<>();
        for (Map.Entry<String, RGridResource> pair : resourceMap.entrySet()) {
            String url = pair.getKey();
            RGridResource resource = pair.getValue();
            String hash = resource.getSha256();
            String hashFormat = resource.getHashFormat();
            synchronized (uploadedResourcesCache) {
                if (!uploadedResourcesCache.contains(hash)) {
                    hashesToCheck.add(new HashObject(hashFormat, hash));
                    hashToResourceUrl.put(hash, url);
                }
            }
        }

        RGridResource domResource = dom.asResource();
        synchronized (uploadedResourcesCache) {
            if (!uploadedResourcesCache.contains(domResource.getSha256())) {
                hashesToCheck.add(new HashObject(domResource.getHashFormat(), domResource.getSha256()));
                hashToResourceUrl.put(domResource.getSha256(), domResource.getUrl());
            }
        }

        if (hashesToCheck.isEmpty()) {
            return new HashMap<>();
        }

        AtomicReference<EyesSyncObject> lock = new AtomicReference<>(new EyesSyncObject(logger, "checkResourceStatus"));
        AtomicReference<Boolean[]> reference = new AtomicReference<>();
        SyncTaskListener<Boolean[]> listener = new SyncTaskListener<>(lock, reference);
        eyesConnector.checkResourceStatus(listener, null, hashesToCheck.toArray(new HashObject[0]));
        synchronized (lock.get()) {
            try {
                lock.get().waitForNotify();
            } catch (InterruptedException ignored) {}
        }

        Boolean[] result = reference.get();
        if (result == null) {
            return new HashMap<>();
        }

        Map<String, RGridResource> missingResources = new HashMap<>();
        for (int i = 0; i < result.length; i++) {
            if (result[i] != null && result[i]) {
                continue;
            }

            String resourceUrl = hashToResourceUrl.get(hashesToCheck.get(i).getHash());
            missingResources.put(resourceUrl, fetchedCacheMap.get(resourceUrl));
        }

        return missingResources;
    }

    void uploadResources(Map<String, RGridResource> resources) {
        for (String url : resources.keySet()) {
            RGridResource resource;
            if (!fetchedCacheMap.containsKey(url)) {
                logger.verbose(String.format("Resource %s requested but never downloaded (maybe a Frame)", url));
                resource = resources.get(url);
            } else {
                resource = fetchedCacheMap.get(url);
            }

            synchronized (uploadedResourcesCache) {
                if (uploadedResourcesCache.contains(resource.getSha256())) {
                    continue;
                }
            }

            logger.verbose("resource(" + resource.getUrl() + ") hash : " + resource.getSha256());
            phaser.register();
            this.eyesConnector.renderPutResource("NONE", resource, putListener);
            synchronized (uploadedResourcesCache) {
                uploadedResourcesCache.add(resource.getSha256());
            }
        }
    }
}
