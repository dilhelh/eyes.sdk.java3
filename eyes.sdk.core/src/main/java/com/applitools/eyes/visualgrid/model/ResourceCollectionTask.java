package com.applitools.eyes.visualgrid.model;

import com.applitools.ICheckSettings;
import com.applitools.ICheckSettingsInternal;
import com.applitools.eyes.Logger;
import com.applitools.eyes.TaskListener;
import com.applitools.eyes.TestResultContainer;
import com.applitools.eyes.UserAgent;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridRunner;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.GeneralUtils;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.*;
import java.util.concurrent.Callable;

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
    private IDebugResourceWriter debugResourceWriter;
    private final TaskListener<List<RenderingTask>> listener;
    private RenderingTask.RenderTaskListener renderTaskListener;

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
        this.logger = runner.getLogger();
        this.runner = runner;
        this.eyesConnector = eyesConnector;
        this.fetchedCacheMap = runner.getCachedResources();
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
            listener.onComplete(renderingTasks);
        } catch (Throwable t) {
            GeneralUtils.logExceptionStackTrace(logger, t);
            listener.onFail();
        }

        return null;
    }

    private List<RenderRequest> buildRenderRequests(FrameData result, Map<String, RGridResource> resourceMapping) {

        RGridDom dom = new RGridDom(result.getCdt(), resourceMapping, result.getUrl(), logger, "buildRenderRequests");

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
}
