package com.applitools.eyes.visualgrid.model;

import com.applitools.eyes.EyesException;
import com.applitools.eyes.Logger;
import com.applitools.eyes.SyncTaskListener;
import com.applitools.eyes.TaskListener;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridRunner;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.EyesSyncObject;
import com.applitools.utils.GeneralUtils;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RenderingTask implements Callable<RenderStatusResults> {

    private static final int FETCH_TIMEOUT_SECONDS = 60;
    public static final int HOUR = 60 * 60 * 1000;

    private final RenderTaskListener listener;
    private final IEyesConnector eyesConnector;
    final List<RenderRequest> renderRequests = new ArrayList<>();
    private final List<VisualGridTask> checkTasks = new ArrayList<>();
    final Map<String, RGridResource> fetchedCacheMap;
    final Map<String, RGridResource> putResourceCache;
    final Set<String> checkResourceCache;
    private final Logger logger;
    private final AtomicBoolean isForcePutNeeded;
    private final Timer timer = new Timer("VG_StopWatch", true);
    private final AtomicBoolean isTimeElapsed = new AtomicBoolean(false);

    // Phaser for syncing all futures downloading resources
    Phaser resourcesPhaser = new Phaser();

    // Listener for putResource tasks
    final TaskListener<Boolean> putListener = new TaskListener<Boolean>() {
        @Override
        public void onComplete(Boolean isSucceeded) {
            try {
                if (!isSucceeded) {
                    logger.log("Failed putting resource");
                }
            } finally {
                resourcesPhaser.arriveAndDeregister();
            }
        }

        @Override
        public void onFail() {
            resourcesPhaser.arriveAndDeregister();
            logger.log("Failed putting resource");
        }
    };

    public interface RenderTaskListener {
        void onRenderSuccess();

        void onRenderFailed(Exception e);
    }

    public RenderingTask(IEyesConnector eyesConnector, RenderRequest renderRequest, VisualGridTask checkTask,
                         VisualGridRunner runner, RenderTaskListener listener) {
        this.eyesConnector = eyesConnector;
        this.renderRequests.add(renderRequest);
        this.checkTasks.add(checkTask);
        this.fetchedCacheMap = runner.getCachedResources();
        this.putResourceCache = runner.getPutResourceCache();
        this.checkResourceCache = runner.getCheckResourceCache();
        this.logger = runner.getLogger();
        this.listener = listener;
        String renderingGridForcePut = GeneralUtils.getEnvString("APPLITOOLS_RENDERING_GRID_FORCE_PUT");
        this.isForcePutNeeded = new AtomicBoolean(renderingGridForcePut != null && renderingGridForcePut.equalsIgnoreCase("true"));
    }

    public void merge(RenderingTask renderingTask) {
        renderRequests.addAll(renderingTask.renderRequests);
        checkTasks.addAll(renderingTask.checkTasks);
    }

    @Override
    public RenderStatusResults call() {
        try {
            logger.verbose("enter");

            logger.verbose("Uploading missing resources");
            resourcesPhaser = new Phaser();
            Map<String, RGridResource> missingResources = checkResourcesStatus();
            uploadResources("NONE", missingResources.keySet(), missingResources, false);
            try {
                if (resourcesPhaser.getRegisteredParties() > 0) {
                    resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException | TimeoutException e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
                resourcesPhaser.forceTermination();
            }

            logger.verbose("Start rendering");
            boolean stillRendering;
            long elapsedTimeStart = System.currentTimeMillis();
            boolean isForcePutAlreadyDone = false;
            List<RunningRender> runningRenders;
            RenderRequest[] asArray = renderRequests.toArray(new RenderRequest[0]);
            do {
                try {
                    runningRenders = this.eyesConnector.render(asArray);
                } catch (Exception e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                    logger.verbose("/render throws exception... sleeping for 1.5s");
                    logger.verbose("ERROR " + e.getMessage());
                    Thread.sleep(1500);
                    try {
                        runningRenders = this.eyesConnector.render(asArray);
                    } catch (Exception e1) {
                        setRenderErrorToTasks();
                        throw new EyesException("Invalid response for render request", e1);
                    }
                }

                logger.verbose("Validation render result");
                if (runningRenders == null || runningRenders.size() == 0) {
                    setRenderErrorToTasks();
                    throw new EyesException("Invalid response for render request");
                }

                for (int i = 0; i < renderRequests.size(); i++) {
                    RenderRequest request = renderRequests.get(i);
                    request.setRenderId(runningRenders.get(i).getRenderId());
                    logger.verbose(String.format("RunningRender: %s", runningRenders.get(i)));
                }

                boolean shouldUploadResources = shouldUploadResources(runningRenders);
                double elapsedTime = ((double) System.currentTimeMillis() - elapsedTimeStart) / 1000;
                stillRendering = (shouldUploadResources && elapsedTime < FETCH_TIMEOUT_SECONDS);
                if (!stillRendering) {
                    continue;
                }

                logger.verbose("Uploading missing resources");
                boolean forcePut = isForcePutNeeded.get() && !isForcePutAlreadyDone;
                uploadResources(runningRenders, forcePut);
                if (forcePut) {
                    isForcePutAlreadyDone = true;
                }
                try {
                    if (resourcesPhaser.getRegisteredParties() > 0) {
                        resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException | TimeoutException e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                    resourcesPhaser.forceTermination();
                }
            } while (stillRendering);

            logger.verbose("Poll rendering status");
            Map<RunningRender, RenderRequest> mapping = mapRequestToRunningRender(runningRenders);
            pollRenderingStatus(mapping);
        } catch (Throwable e) {
            GeneralUtils.logExceptionStackTrace(logger, e);
            for (VisualGridTask checkTask : checkTasks) {
                checkTask.setExceptionAndAbort(e);
            }
            listener.onRenderFailed(new EyesException("Failed rendering", e));
        }
        logger.verbose("Finished rendering task - exit");

        return null;
    }

    /**
     * Checks with the server what resources are missing.
     * @return All the missing resources to upload.
     */
    Map<String, RGridResource> checkResourcesStatus() {
        List<HashObject> hashesToCheck = new ArrayList<>();
        Map<String, String> hashToResourceUrl = new HashMap<>();
        for (Map.Entry<String, RGridResource> pair : fetchedCacheMap.entrySet()) {
            String hash = pair.getValue().getSha256();
            String hashFormat = pair.getValue().getHashFormat();
            synchronized (checkResourceCache) {
                if (!checkResourceCache.contains(hash)) {
                    hashesToCheck.add(new HashObject(hashFormat, hash));
                    hashToResourceUrl.put(hash, pair.getKey());
                    checkResourceCache.add(hash);
                }
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

    void uploadResources(List<RunningRender> runningRenders, boolean forcePut) {
        logger.verbose("enter");
        resourcesPhaser = new Phaser();
        for (int i = 0; i < renderRequests.size(); i++) {
            RenderRequest renderRequest = renderRequests.get(i);
            RunningRender runningRender = runningRenders.get(i);
            RGridDom dom = renderRequest.getDom();
            Map<String, RGridResource> resources = renderRequest.getResources();

            // Uploading DOM
            if (runningRender.isNeedMoreDom() || forcePut) {
                try {
                    resourcesPhaser.register();
                    this.eyesConnector.renderPutResource(runningRender.getRenderId(), dom.asResource(), putListener);
                } catch (Throwable e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                }
            }

            // Uploading missing resources
            logger.verbose("creating PutFutures for " + runningRenders.size() + " runningRenders");
            Collection<String> urls = forcePut ? resources.keySet() : runningRender.getNeedMoreResources();
            uploadResources(runningRender.getRenderId(), urls, resources, forcePut);
        }
        logger.verbose("exit");
    }

    private void uploadResources(String renderId, Collection<String> urls, Map<String, RGridResource> resources, boolean forcePut) {
        for (String url : urls) {
            if (putResourceCache.containsKey(url) && ! forcePut) {
                continue;
            }

            RGridResource resource;
            if (!fetchedCacheMap.containsKey(url)) {
                logger.verbose(String.format("Resource %s requested but never downloaded (maybe a Frame)", url));
                resource = resources.get(url);
            } else {
                resource = fetchedCacheMap.get(url);
            }

            if (resource == null) {
                logger.log(String.format("Illegal state: resource is null for url %s", url));
                continue;
            }

            logger.verbose("resource(" + resource.getUrl() + ") hash : " + resource.getSha256());
            resourcesPhaser.register();
            this.eyesConnector.renderPutResource(renderId, resource, putListener);
            String contentType = resource.getContentType();
            synchronized (putResourceCache) {
                if (!putResourceCache.containsKey(url) && (contentType != null && !contentType.equalsIgnoreCase(RGridDom.CONTENT_TYPE))) {
                    putResourceCache.put(url, resource);
                }
            }
        }
    }

    private void setRenderErrorToTasks() {
        for (RenderRequest renderRequest : renderRequests) {
            renderRequest.getCheckTask().setRenderError(null, "Invalid response for render request");
        }
    }

    private Map<RunningRender, RenderRequest> mapRequestToRunningRender(List<RunningRender> runningRenders) {
        Map<RunningRender, RenderRequest> mapping = new HashMap<>();
        for (int i = 0; i < renderRequests.size(); i++) {
            mapping.put(runningRenders.get(i), renderRequests.get(i));
        }
        return mapping;
    }

    private boolean shouldUploadResources(List<RunningRender> runningRenders) {
        for (RunningRender runningRender : runningRenders) {
            RenderStatus renderStatus = runningRender.getRenderStatus();
            if (renderStatus.equals(RenderStatus.NEED_MORE_RESOURCE) ||
                    renderStatus.equals(RenderStatus.NEED_MORE_DOM) ||
                            runningRender.isNeedMoreDom()) {
                return true;
            }
        }

        return false;
    }

    private List<String> getRenderIds(Collection<RunningRender> runningRenders) {
        List<String> ids = new ArrayList<>();
        for (RunningRender runningRender : runningRenders) {
            ids.add(runningRender.getRenderId());
        }
        return ids;
    }

    private void pollRenderingStatus(Map<RunningRender, RenderRequest> runningRenders) {
        logger.verbose("enter");
        List<String> ids = getRenderIds(runningRenders.keySet());
        logger.verbose("render ids : " + ids);
        timer.schedule(new TimeoutTask(), HOUR);
        do {
            List<RenderStatusResults> renderStatusResultsList;
            try {
                renderStatusResultsList = this.eyesConnector.renderStatusById(ids.toArray(new String[0]));
            } catch (Exception e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
                continue;
            }
            if (renderStatusResultsList == null || renderStatusResultsList.isEmpty() || renderStatusResultsList.get(0) == null) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                }
                continue;
            }

            sampleRenderingStatus(runningRenders, ids, renderStatusResultsList);

            if (ids.size() > 0) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                }
            }

        } while (!ids.isEmpty() && !isTimeElapsed.get());

        timer.cancel();
        if (!ids.isEmpty()) {
            logger.verbose("Render ids that didn't complete in time : ");
            logger.verbose(ids.toString());
        }

        for (String id : ids) {
            for (Map.Entry<RunningRender,RenderRequest> kvp : runningRenders.entrySet()) {
                RunningRender renderedRender = kvp.getKey();
                RenderRequest renderRequest = kvp.getValue();
                String renderId = renderedRender.getRenderId();
                if (renderId.equalsIgnoreCase(id)) {
                    logger.verbose("removing failed render id: " + id);
                    VisualGridTask checkTask = renderRequest.getCheckTask();
                    checkTask.setRenderError(id, "too long rendering(rendering exceeded 150 sec)");
                    break;
                }
            }
        }

        logger.verbose("marking task as complete");
        listener.onRenderSuccess();
        logger.verbose("exit");
    }

    private void sampleRenderingStatus(Map<RunningRender, RenderRequest> runningRenders, List<String> ids, List<RenderStatusResults> renderStatusResultsList) {
        logger.verbose("enter - renderStatusResultsList.size: " + renderStatusResultsList.size());

        for (int i = 0, j = 0; i < renderStatusResultsList.size(); i++) {
            RenderStatusResults renderStatusResults = renderStatusResultsList.get(i);
            if (renderStatusResults == null) {
                continue;
            }

            RenderStatus renderStatus = renderStatusResults.getStatus();
            boolean isRenderedStatus = renderStatus == RenderStatus.RENDERED;
            boolean isErrorStatus = renderStatus == RenderStatus.ERROR;
            logger.verbose("renderStatusResults - " + renderStatusResults);
            if (isRenderedStatus || isErrorStatus) {
                String removedId = ids.remove(j);
                for (Map.Entry<RunningRender, RenderRequest> kvp: runningRenders.entrySet()) {
                    RunningRender renderedRender = kvp.getKey();
                    RenderRequest renderRequest = kvp.getValue();
                    String renderId = renderedRender.getRenderId();
                    if (renderId.equalsIgnoreCase(removedId)) {
                        VisualGridTask checkTask = renderRequest.getCheckTask();
                        logger.verbose("setting visualGridTask " + checkTask + " render result: " + renderStatusResults);
                        String error = renderStatusResults.getError();
                        if (error != null) {
                            GeneralUtils.logExceptionStackTrace(logger, new Exception(error));
                            checkTask.setRenderError(renderId, error);
                        }
                        checkTask.setRenderResult(renderStatusResults);
                        break;
                    }
                }
            } else {
                j++;
            }
        }
        logger.verbose("exit");
    }

    public boolean isReady() {
        for (VisualGridTask checkTask : checkTasks) {
            if (!checkTask.getRunningTest().isTestOpen()) {
                return false;
            }
        }

        return true;
    }

    private class TimeoutTask extends TimerTask {
        @Override
        public void run() {
            logger.verbose("VG is Timed out!");
            isTimeElapsed.set(true);
        }
    }
}