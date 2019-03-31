package com.applitools.eyes.selenium;

import com.applitools.eyes.config.Configuration;
import com.applitools.eyes.visualgridclient.model.RenderBrowserInfo;

import java.util.List;

public interface IConfigurationGetter extends com.applitools.eyes.config.IConfigurationGetter {

    boolean getForceFullPageScreenshot();

    int getWaitBeforeScreenshots();

    StitchMode getStitchMode();

    boolean getHideScrollbars();

    boolean getHideCaret();

    List<RenderBrowserInfo> getBrowsersInfo();

    boolean isThrowExceptionOn();

    String getTestName();

    boolean isRenderingConfig();

    Configuration cloneConfig();
}