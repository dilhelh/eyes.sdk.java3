package com.applitools.eyes.visualgrid.model;

import com.applitools.eyes.EyesException;
import com.applitools.eyes.Logger;
import com.applitools.eyes.TaskListener;
import com.applitools.eyes.UserAgent;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridRunner;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.GeneralUtils;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderingTask implements Callable<RenderStatusResults>, CompletableTask {

    private static final int FETCH_TIMEOUT_SECONDS = 60;
    public static final int HOUR = 60 * 60 * 1000;

    private final RenderTaskListener listener;
    private final IEyesConnector eyesConnector;
    final RenderRequest renderRequest;
    private final VisualGridTask checkTask;
    private final UserAgent userAgent;
    final Map<String, RGridResource> fetchedCacheMap;
    final Map<String, RGridResource> putResourceCache;
    private final Logger logger;
    private final AtomicBoolean isTaskComplete = new AtomicBoolean(false);
    private AtomicBoolean isForcePutNeeded;
    private String pageUrl;
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

    /**
     * For tests only
     */
    RenderingTask(IEyesConnector eyesConnector, RenderRequest renderRequest, VisualGridTask checkTask,
                  UserAgent userAgent, RenderTaskListener listener) {
        this.eyesConnector = eyesConnector;
        this.renderRequest = renderRequest;
        this.checkTask = checkTask;
        this.userAgent = userAgent;
        fetchedCacheMap = new HashMap<>();
        logger = new Logger();
        putResourceCache = new HashMap<>();
        this.listener = listener;
    }

    public RenderingTask(IEyesConnector eyesConnector, String pageUrl, RenderRequest renderRequest,
                         VisualGridTask checkTask, VisualGridRunner renderingGridManager,
                         RenderTaskListener listener, UserAgent userAgent) {
        this.eyesConnector = eyesConnector;
        this.pageUrl = pageUrl;
        this.renderRequest = renderRequest;
        this.checkTask = checkTask;
        this.fetchedCacheMap = renderingGridManager.getCachedResources();
        this.putResourceCache = renderingGridManager.getPutResourceCache();
        this.logger = renderingGridManager.getLogger();
        this.userAgent = userAgent;
        this.listener = listener;
        String renderingGridForcePut = GeneralUtils.getEnvString("APPLITOOLS_RENDERING_GRID_FORCE_PUT");
        this.isForcePutNeeded = new AtomicBoolean(renderingGridForcePut != null && renderingGridForcePut.equalsIgnoreCase("true"));
    }

    @Override
    public RenderStatusResults call() {
        try {
            logger.verbose("enter");

            logger.verbose("step 1");
            boolean stillRunning;
            long elapsedTimeStart = System.currentTimeMillis();
            boolean isForcePutAlreadyDone = false;
            List<RunningRender> runningRenders;
            do {
                try {
                    runningRenders = this.eyesConnector.render(renderRequest);
                } catch (Exception e) {
                    GeneralUtils.logExceptionStackTrace(logger, e);
                    logger.verbose("/render throws exception... sleeping for 1.5s");
                    logger.verbose("ERROR " + e.getMessage());
                    Thread.sleep(1500);
                    try {
                        runningRenders = this.eyesConnector.render(renderRequest);
                    } catch (Exception e1) {
                        setRenderErrorToTasks();
                        throw new EyesException("Invalid response for render request", e1);
                    }
                }

                logger.verbose("step 2.1");
                if (runningRenders == null || runningRenders.size() == 0) {
                    setRenderErrorToTasks();
                    throw new EyesException("Invalid response for render request");
                }

                RunningRender runningRender = runningRenders.get(0);
                renderRequest.setRenderId(runningRender.getRenderId());
                logger.verbose(String.format("RunningRender: %s", runningRender));
                logger.verbose("step 2.2");

                RenderStatus worstStatus = runningRender.getRenderStatus();

                worstStatus = calcWorstStatus(runningRenders, worstStatus);

                boolean isNeedMoreDom = runningRender.isNeedMoreDom();

                if (isForcePutNeeded.get() && !isForcePutAlreadyDone) {
                    forcePutAllResources(renderRequest.getResources(), renderRequest.getDom(), runningRender);
                    isForcePutAlreadyDone = true;
                    try {
                        if (resourcesPhaser.getRegisteredParties() > 0) {
                            resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException | TimeoutException e) {
                        GeneralUtils.logExceptionStackTrace(logger, e);
                        resourcesPhaser.forceTermination();
                    }
                }

                logger.verbose("step 2.3");
                double elapsedTime = ((double) System.currentTimeMillis() - elapsedTimeStart) / 1000;
                stillRunning = (worstStatus == RenderStatus.NEED_MORE_RESOURCE || isNeedMoreDom) && elapsedTime < FETCH_TIMEOUT_SECONDS;
                if (stillRunning) {
                    sendMissingResources(runningRenders, renderRequest.getDom(), renderRequest.getResources(), isNeedMoreDom);
                    try {
                        if (resourcesPhaser.getRegisteredParties() > 0) {
                            resourcesPhaser.awaitAdvanceInterruptibly(0, 30, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException | TimeoutException e) {
                        GeneralUtils.logExceptionStackTrace(logger, e);
                        resourcesPhaser.forceTermination();
                    }
                }

                logger.verbose("step 2.4");

            } while (stillRunning);

            Map<RunningRender, RenderRequest> mapping = new HashMap<>();
            mapping.put(runningRenders.get(0), renderRequest);

            logger.verbose("step 3");
            pollRenderingStatus(mapping);
        } catch (Throwable e) {
            GeneralUtils.logExceptionStackTrace(logger, e);
            checkTask.setExceptionAndAbort(e);
            listener.onRenderFailed(new EyesException("Failed rendering", e));
        }
        logger.verbose("Finished rendering task - exit");

        return null;
    }

    private void forcePutAllResources(Map<String, RGridResource> resources, RGridDom dom, RunningRender runningRender) {
        resourcesPhaser = new Phaser();
        Set<String> strings = resources.keySet();
        try {
            resourcesPhaser.register();
            this.eyesConnector.renderPutResource(runningRender, dom.asResource(), userAgent.getOriginalUserAgentString(), putListener);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        for (String url : strings) {
            try {
                logger.verbose("trying to get url from map - " + url);
                RGridResource resource;
                if (!fetchedCacheMap.containsKey(url)) {
                    resource = resources.get(url);
                } else {
                    resource = fetchedCacheMap.get(url);
                }

                if (resource == null) {
                    logger.log(String.format("Illegal state: resource is null for url %s", url));
                    continue;
                }

                resourcesPhaser.register();
                this.eyesConnector.renderPutResource(runningRender, resource, userAgent.getOriginalUserAgentString(), putListener);
                logger.verbose("locking putResourceCache");
                synchronized (putResourceCache) {
                    String contentType = resource.getContentType();
                    if (contentType != null && !contentType.equalsIgnoreCase(RGridDom.CONTENT_TYPE)) {
                        putResourceCache.put(url, resource);
                    }
                }
            } catch (Exception e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
            }
        }
    }

    private void setRenderErrorToTasks() {
        renderRequest.getCheckTask().setRenderError(null, "Invalid response for render request");
    }

    private RenderStatus calcWorstStatus(List<RunningRender> runningRenders, RenderStatus worstStatus) {
        LOOP:
        for (RunningRender runningRender : runningRenders) {
            switch (runningRender.getRenderStatus()) {
                case NEED_MORE_RESOURCE:
                    if (worstStatus == RenderStatus.RENDERED || worstStatus == RenderStatus.RENDERING) {
                        worstStatus = RenderStatus.NEED_MORE_RESOURCE;
                    }
                    break;
                case ERROR:
                    worstStatus = RenderStatus.ERROR;
                    break LOOP;
            }
        }
        return worstStatus;
    }

    private List<String> getRenderIds(Collection<RunningRender> runningRenders) {
        List<String> ids = new ArrayList<>();
        for (RunningRender runningRender : runningRenders) {
            ids.add(runningRender.getRenderId());
        }
        return ids;
    }

    private void sendMissingResources(List<RunningRender> runningRenders, RGridDom dom, Map<String, RGridResource> resources, boolean isNeedMoreDom) {
        logger.verbose("enter");
        resourcesPhaser = new Phaser();
        if (isNeedMoreDom) {
            RunningRender runningRender = runningRenders.get(0);
            try {
                resourcesPhaser.register();
                this.eyesConnector.renderPutResource(runningRender, dom.asResource(), userAgent.getOriginalUserAgentString(), putListener);
            } catch (Throwable e) {
                GeneralUtils.logExceptionStackTrace(logger, e);
            }
        }

        logger.verbose("creating PutFutures for " + runningRenders.size() + " runningRenders");

        for (RunningRender runningRender : runningRenders) {
            createPutFutures(runningRender, resources);
        }
        logger.verbose("exit");
    }

    void createPutFutures(RunningRender runningRender, Map<String, RGridResource> resources) {
        List<String> needMoreResources = runningRender.getNeedMoreResources();
        for (String url : needMoreResources) {
            if (putResourceCache.containsKey(url)) {
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
            this.eyesConnector.renderPutResource(runningRender, resource, userAgent.getOriginalUserAgentString(), putListener);
            String contentType = resource.getContentType();
            if (!putResourceCache.containsKey(url) && (contentType != null && !contentType.equalsIgnoreCase(RGridDom.CONTENT_TYPE))) {
                synchronized (putResourceCache) {
                    putResourceCache.put(url, resource);
                }
            }
        }
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

        logger.verbose("marking task as complete: " + pageUrl);
        this.isTaskComplete.set(true);
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
                        logger.verbose("setting visualGridTask " + checkTask + " render result: " + renderStatusResults + " to url " + pageUrl);
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
        return checkTask.getRunningTest().isTestOpen();
    }

    public boolean isTaskComplete() {
        return isTaskComplete.get();
    }

    private class TimeoutTask extends TimerTask {
        @Override
        public void run() {
            logger.verbose("VG is Timed out!");
            isTimeElapsed.set(true);
        }
    }
}