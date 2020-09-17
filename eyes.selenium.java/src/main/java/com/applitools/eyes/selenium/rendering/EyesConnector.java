package com.applitools.eyes.selenium.rendering;

import com.applitools.ICheckSettings;
import com.applitools.eyes.*;
import com.applitools.eyes.capture.AppOutputWithScreenshot;
import com.applitools.eyes.config.Configuration;
import com.applitools.eyes.fluent.ICheckSettingsInternal;
import com.applitools.eyes.visualgrid.model.*;
import com.applitools.eyes.visualgrid.services.IEyesConnector;
import com.applitools.eyes.visualgrid.services.VisualGridTask;
import com.applitools.utils.ClassVersionGetter;
import com.applitools.utils.EyesSyncObject;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

class EyesConnector extends EyesBase implements IEyesConnector, IBatchCloser {
    private final RenderBrowserInfo browserInfo;
    private String userAgent;
    private String device;
    private RectangleSize deviceSize;
    private Configuration configuration;
    private String appName;
    private String testName;

    public EyesConnector(Configuration configuration, List<PropertyData> properties, RenderBrowserInfo browserInfo) {
        this.configuration = configuration;
        this.browserInfo = browserInfo;
        if (properties != null) {
            for (PropertyData property : properties) {
                this.addProperty(property);
            }
        }
    }

    /**
     * Starts a new test without setting the viewport size of the AUT.
     */
    public void open(Configuration config, String appName, String testName) {
        this.configuration = config;
        this.appName = appName;
        this.testName = testName;
        logger.verbose("opening EyesConnector with viewport size: " + browserInfo.getViewportSize());
        openBase();
    }

    public Future<?> getResource(URI url, String userAgent, String refererUrl, TaskListener<RGridResource> listener) {
        return getServerConnector().downloadResource(url, userAgent, refererUrl, listener);
    }

    @Override
    public Future<?> renderPutResource(RunningRender runningRender, RGridResource resource, TaskListener<Boolean> listener) {
        return getServerConnector().renderPutResource(runningRender, resource, listener);
    }

    @Override
    public List<RunningRender> render(RenderRequest... renderRequests) {
        final AtomicReference<List<RunningRender>> reference = new AtomicReference<>();
        final AtomicReference<EyesSyncObject> lock = new AtomicReference<>(new EyesSyncObject(logger, "render"));
        getServerConnector().render(new SyncTaskListener<>(lock, reference), renderRequests);
        synchronized (lock.get()) {
            try {
                lock.get().waitForNotify();
            } catch (InterruptedException ignored) {}
        }

        return reference.get();
    }

    public List<RenderStatusResults> renderStatusById(String... renderIds) {
        final AtomicReference<List<RenderStatusResults>> reference = new AtomicReference<>();
        final AtomicReference<EyesSyncObject> lock = new AtomicReference<>(new EyesSyncObject(logger, "renderStatusById"));
        getServerConnector().renderStatusById(new SyncTaskListener<>(lock, reference), renderIds);
        synchronized (lock.get()) {
            try {
                lock.get().waitForNotify();
            } catch (InterruptedException ignored) {}
        }

        return reference.get();
    }

    public MatchResult matchWindow(String resultImageURL, String domLocation, ICheckSettings checkSettings,
                                   List<? extends IRegion> regions, List<VisualGridSelector[]> regionSelectors, Location location,
                                   String renderId, String source, RectangleSize virtualViewport) {
        ICheckSettingsInternal checkSettingsInternal = (ICheckSettingsInternal) checkSettings;
        if (checkSettingsInternal.getStitchContent() == null) {
            checkSettings.fully();
        }

        MatchWindowTask matchWindowTask = new MatchWindowTask(this.logger, getServerConnector(), this.runningSession, getConfigurationInstance().getMatchTimeout(), this);
        ImageMatchSettings imageMatchSettings = MatchWindowTask.createImageMatchSettings(checkSettingsInternal, this);
        String tag = checkSettingsInternal.getName();
        AppOutput appOutput = new AppOutput(tag, null, domLocation, resultImageURL, virtualViewport);
        AppOutputWithScreenshot appOutputWithScreenshot = new AppOutputWithScreenshot(appOutput, null, location);
        return matchWindowTask.performMatch(appOutputWithScreenshot, tag, checkSettingsInternal, imageMatchSettings, regions, regionSelectors, this, renderId, source);
    }

    protected String getBaseAgentId() {
        return "eyes.selenium.visualgrid.java/" + ClassVersionGetter.CURRENT_VERSION;
    }

    protected RectangleSize getViewportSize() {
        return RectangleSize.EMPTY;
    }

    protected Configuration setViewportSize(RectangleSize size) {
        logger.log("WARNING setViewportSize() was called in Visual-Grid context");
        return getConfigurationInstance();
    }

    protected String getInferredEnvironment() {
        return "useragent:" + userAgent;
    }

    protected EyesScreenshot getScreenshot(Region targetRegion, ICheckSettingsInternal checkSettingsInternal) {
        return null;
    }

    protected String getTitle() {
        return null;
    }

    protected String getAUTSessionId() {
        return null;
    }

    @Override
    protected Configuration getConfigurationInstance() {
        return configuration;
    }

    public void setRenderInfo(RenderingInfo renderInfo) {
        getServerConnector().setRenderingInfo(renderInfo);
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    protected void openLogger() {
        // do nothing.
    }

    protected void closeLogger() {
        // do nothing.
    }

    public String tryCaptureDom() {
        return null;
    }

    public Configuration setApiKey(String apiKey) {
        return super.setApiKey(apiKey);
    }

    public Configuration setServerUrl(URI serverUrl) {
        return super.setServerUrl(serverUrl);
    }

    public void setBranchName(String branchName) {
        getConfigurationInstance().setBranchName(branchName);
    }

    public void setParentBranchName(String parentBranchName) {
        getConfigurationInstance().setParentBranchName(parentBranchName);
    }

    public void setDevice(String device) {
        this.device = device;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This override also checks for mobile operating system.
     */

    protected AppEnvironment getAppEnvironment() {
        AppEnvironment appEnv = super.getAppEnvironment();
        appEnv.setDeviceInfo(device);
        if (userAgent == null) {
            appEnv.setOs(VisualGridTask.toPascalCase(browserInfo.getPlatform()));
            String browserName = BrowserNames.getBrowserName(browserInfo.getBrowserType());
            appEnv.setHostingApp(browserName);
        }
        logger.log("Done!");
        return appEnv;
    }

    public RectangleSize getDeviceSize() {
        return deviceSize;
    }

    public void setDeviceSize(RectangleSize deviceSize) {
        this.deviceSize = deviceSize;
    }

    public RunningSession getSession() {
        return this.runningSession;
    }

    protected RectangleSize getViewportSizeForOpen() {
        if (device != null) {
            return deviceSize;
        } else if (browserInfo.getViewportSize() != null) {
            return browserInfo.getViewportSize();
        } else {
            //this means it's a emulationInfo
            if (browserInfo.getEmulationInfo() instanceof EmulationDevice) {
                EmulationDevice emulationDevice = (EmulationDevice) browserInfo.getEmulationInfo();
                return new RectangleSize(emulationDevice.getWidth(), emulationDevice.getHeight());
            }
        }
        return super.getViewportSizeForOpen();
    }

    protected String getBaselineEnvName() {
        String baselineEnvName = this.browserInfo.getBaselineEnvName();
        if (baselineEnvName != null) {
            return baselineEnvName;
        }
        return getConfigurationInstance().getBaselineEnvName();
    }

    protected String getAppName() {
        return this.appName;
    }

    protected String getTestName() {
        return this.testName;
    }

    public void closeBatch(String batchId) {
        getServerConnector().closeBatch(batchId);
    }
}