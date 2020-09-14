package com.applitools.eyes.visualgrid.services;

import com.applitools.ICheckSettings;
import com.applitools.ICheckSettingsInternal;
import com.applitools.connectivity.ServerConnector;
import com.applitools.eyes.*;
import com.applitools.eyes.config.Configuration;
import com.applitools.eyes.exceptions.DiffsFoundException;
import com.applitools.eyes.visualgrid.model.*;
import com.applitools.utils.ArgumentGuard;
import com.applitools.utils.GeneralUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class VisualGridTask implements Callable<TestResultContainer> {


    private final Logger logger;

    private boolean isSent;
    private String source;

    public enum TaskType {OPEN, CHECK, CLOSE, ABORT}

    private Configuration configuration;
    private TestResults testResults;

    private IEyesConnector eyesConnector;
    private TaskType type;

    private RenderStatusResults renderResult;
    private final List<VGTaskListener> listeners = new ArrayList<>();
    private ICheckSettingsInternal checkSettings;

    private final RunningTest runningTest;
    private Throwable exception;

    private final List<VisualGridSelector[]> regionSelectors;

    private boolean isReadyForRender = false;

    interface VGTaskListener {

        void onTaskComplete(VisualGridTask visualGridTask);

        void onTaskFailed(Throwable e, VisualGridTask visualGridTask);

        void onRenderComplete();

    }

    /******** BEGIN - PUBLIC FOR TESTING PURPOSES ONLY ********/
    public VisualGridTask(TaskType taskType, Logger logger, RunningTest runningTest) {
        this.logger = logger;
        this.type = taskType;
        this.runningTest = runningTest;
        this.regionSelectors = null;
    }

    /******** END - PUBLIC FOR TESTING PURPOSES ONLY ********/

    public VisualGridTask(Configuration configuration, TestResults testResults, IEyesConnector eyesConnector, TaskType type, VGTaskListener runningTestListener,
                          ICheckSettings checkSettings, RunningTest runningTest, List<VisualGridSelector[]> regionSelectors, String source) {
        this.configuration = configuration;
        this.testResults = testResults;
        this.eyesConnector = eyesConnector;
        this.type = type;
        this.regionSelectors = regionSelectors;
        this.listeners.add(runningTestListener);
        this.logger = runningTest.getLogger();
        this.source = source;
        if (checkSettings != null) {
            this.checkSettings = (ICheckSettingsInternal) checkSettings;
            this.checkSettings = this.checkSettings.clone();
        }
        this.runningTest = runningTest;
    }

    public RenderBrowserInfo getBrowserInfo() {
        return runningTest.getBrowserInfo();
    }

    public TaskType getType() {
        return type;
    }

    boolean isSent() {
        return isSent;
    }

    void setIsSent() {
        this.isSent = true;
    }

    @Override
    public TestResultContainer call() {
        try {
            testResults = null;
            switch (type) {
                case OPEN:
                    logger.verbose("VisualGridTask.run opening task");
                    String userAgent = getUserAgent();
                    RectangleSize deviceSize = getDeviceSize();
                    if (deviceSize == null) {
                        deviceSize = RectangleSize.EMPTY;
                    }
                    deviceSize = deviceSize.isEmpty() ? getBrowserInfo().getViewportSize() : deviceSize;
                    logger.verbose("device size: " + deviceSize);
                    eyesConnector.setUserAgent(userAgent);
                    eyesConnector.setDeviceSize(deviceSize);
                    eyesConnector.open(configuration, runningTest.getAppName(), runningTest.getTestName());
                    logger.verbose("Eyes Open Done.");
                    break;

                case CHECK:
                    logger.verbose("VisualGridTask.run check task");

                    String imageLocation = renderResult.getImageLocation();
                    String domLocation = renderResult.getDomLocation();

                    List<VGRegion> vgRegions = renderResult.getSelectorRegions();
                    List<IRegion> regions = new ArrayList<>();
                    if (vgRegions != null) {
                        for (VGRegion reg : vgRegions) {
                            if (reg.getError() != null) {
                                logger.log(String.format("Warning: region error: %s", reg.getError()));
                            } else {
                                regions.add(reg);
                            }
                        }
                    }
                    if (imageLocation == null) {
                        logger.verbose("CHECKING IMAGE WITH NULL LOCATION - ");
                        logger.verbose(renderResult.toString());
                    }
                    Location location = null;
                    if (regionSelectors.size() > 0) {
                        VisualGridSelector[] targetSelector = regionSelectors.get(regionSelectors.size() - 1);
                        if (targetSelector.length > 0 && "target".equals(targetSelector[0].getCategory())) {
                            location = regions.get(regions.size() - 1).getLocation();
                        }
                    }

                    eyesConnector.matchWindow(imageLocation, domLocation, (ICheckSettings) checkSettings, regions,
                            this.regionSelectors, location, renderResult.getRenderId(), source, renderResult.getVisualViewport());
                    logger.verbose("match done");
                    break;

                case CLOSE:
                    logger.verbose("VisualGridTask.run close task");
                    try {
                        testResults = eyesConnector.close(true);
                    } catch (Throwable e) {
                        GeneralUtils.logExceptionStackTrace(logger, e);
                        if (e instanceof DiffsFoundException) {
                            DiffsFoundException diffException = (DiffsFoundException) e;
                            testResults = diffException.getTestResults();
                        }
                        this.exception = e;

                    }
                    logger.verbose("Eyes Close Done.");
                    break;

                case ABORT:
                    logger.verbose("VisualGridTask.run abort task");
                    testResults = eyesConnector.abortIfNotClosed();
                    logger.verbose("Closing a not opened test");
            }

            TestResultContainer testResultContainer = new TestResultContainer(testResults, runningTest.getBrowserInfo(), this.exception);
            notifySuccessAllListeners();
            return testResultContainer;
        } catch (Throwable e) {
            GeneralUtils.logExceptionStackTrace(logger, e);
            this.exception = new Error(e);
            notifyFailureAllListeners(new Error(e));
        }
        return null;
    }

    public static String toPascalCase(String str) {
        ArgumentGuard.notNullOrEmpty(str, "str");
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private void notifySuccessAllListeners() {
        for (VGTaskListener listener : listeners) {
            listener.onTaskComplete(this);
        }
    }

    private void notifyFailureAllListeners(Error e) {
        for (VGTaskListener listener : listeners) {
            listener.onTaskFailed(e, this);
        }
    }

    private void notifyRenderCompleteAllListeners() {
        for (VGTaskListener listener : listeners) {
            listener.onRenderComplete();
        }
    }

    public IEyesConnector getEyesConnector() {
        return eyesConnector;
    }

    public void setRenderResult(RenderStatusResults renderResult) {
        logger.verbose("enter");
        this.renderResult = renderResult;
        notifyRenderCompleteAllListeners();
        logger.verbose("exit");
    }

    public boolean isTaskReadyToCheck() {
        return this.renderResult != null || this.exception != null;
    }

    public RunningTest getRunningTest() {
        return runningTest;
    }

    public void addListener(VGTaskListener listener) {
        this.listeners.add(listener);
    }

    public void setRenderError(String renderId, String error) {
        logger.verbose("enter - renderId: " + renderId);
        for (VGTaskListener listener : listeners) {
            exception = new InstantiationError("Render Failed for " + this.getBrowserInfo() + " (renderId: " + renderId + ") with reason: " + error);
            listener.onTaskFailed(exception, this);
        }
        logger.verbose("exit - renderId: " + renderId);
    }

    private String getUserAgent() {
        if (getBrowserInfo().getEmulationInfo() != null || getBrowserInfo().getIosDeviceInfo() != null) {
            return null;
        }

        String browser = getBrowserInfo().getBrowserType().getName();
        Map<String, String> userAgents = eyesConnector.getUserAgents();
        if (!userAgents.containsKey(browser)) {
            logger.verbose(String.format("could not find browser %s in list", browser));
            return null;
        }

        return userAgents.get(browser);
    }

    private RectangleSize getDeviceSize() {
        IosDeviceInfo iosDeviceInfo = getBrowserInfo().getIosDeviceInfo();
        EmulationBaseInfo emulationBaseInfo = getBrowserInfo().getEmulationInfo();
        if (iosDeviceInfo == null && emulationBaseInfo == null) {
            return configuration.getViewportSize();
        }

        if (emulationBaseInfo != null) {
            return getDeviceSizeFromServer(emulationBaseInfo.getDeviceName(), emulationBaseInfo.getScreenOrientation(),
                    ServerConnector.EMULATED_DEVICES_PATH);
        }

        return getDeviceSizeFromServer(iosDeviceInfo.getDeviceName(), iosDeviceInfo.getScreenOrientation(),
                ServerConnector.IOS_DEVICES_PATH);
    }

    private RectangleSize getDeviceSizeFromServer(String deviceName, ScreenOrientation screenOrientation, String path) {
        Map<String, DeviceSize> devicesSizes = eyesConnector.getDevicesSizes(path);
        if (!devicesSizes.containsKey(deviceName)) {
            logger.verbose(String.format("could not find device %s in list", deviceName));
        }

        if (screenOrientation.equals(ScreenOrientation.PORTRAIT)){
            return devicesSizes.get(deviceName).getPortrait();
        }

        return devicesSizes.get(deviceName).getLandscape();
    }

    public Throwable getException() {
        return exception;
    }

    public void setException(Throwable exception) {
        this.exception = exception;
    }

    public void setExceptionAndAbort(Throwable exception) {
        logger.verbose("aborting task with exception");
        this.exception = exception;
        if (type == TaskType.CLOSE) {
            type = TaskType.ABORT;
        }
        runningTest.abort(true, exception);
    }

    @Override
    public String toString() {
        return "VisualGridTask - Type: " + type + " ; Browser Info: " + getBrowserInfo();
    }

    public RunningSession getSession() {
        return this.eyesConnector.getSession();
    }

    public boolean isReadyForRender() {
        return isReadyForRender;
    }

    public void setReadyForRender() {
        isReadyForRender = true;
    }
}
