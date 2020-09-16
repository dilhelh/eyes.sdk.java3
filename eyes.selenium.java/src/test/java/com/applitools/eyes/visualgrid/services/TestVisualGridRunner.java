package com.applitools.eyes.visualgrid.services;

import com.applitools.connectivity.MockServerConnector;
import com.applitools.connectivity.ServerConnector;
import com.applitools.eyes.*;
import com.applitools.eyes.config.Configuration;
import com.applitools.eyes.config.ConfigurationProvider;
import com.applitools.eyes.selenium.Eyes;
import com.applitools.eyes.selenium.fluent.Target;
import com.applitools.eyes.selenium.rendering.VisualGridEyes;
import com.applitools.eyes.utils.SeleniumUtils;
import com.applitools.eyes.visualgrid.model.*;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

public class TestVisualGridRunner {

    Configuration configuration = new Configuration();
    ConfigurationProvider configurationProvider = new ConfigurationProvider() {
        @Override
        public Configuration get() {
            return configuration;
        }
    };

    @BeforeMethod
    public void beforeEach() {
        configuration = new Configuration();
    }

    @Test
    public void testFlow() {
        final VisualGridRunner runner = new VisualGridRunner(10);

        final AtomicReference<String> errorMessage = new AtomicReference<>();
        ServerConnector serverConnector = spy(new MockServerConnector());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                IRenderingEyes eyes = runner.allEyes.iterator().next();
                if (!eyes.getAllRunningTests().get(0).isTestOpen()) {
                    errorMessage.set("Render called before open");
                }

                invocation.callRealMethod();
                return null;
            }
        }).when(serverConnector).render(ArgumentMatchers.<TaskListener<List<RunningRender>>>any(), ArgumentMatchers.<RenderRequest[]>any());

        Eyes eyes = new Eyes(runner);
        eyes.setLogHandler(new StdoutLogHandler());
        Configuration configuration = eyes.getConfiguration();
        configuration.addBrowser(new IosDeviceInfo(IosDeviceName.iPhone_7));
        eyes.setConfiguration(configuration);
        eyes.setServerConnector(serverConnector);
        WebDriver driver = SeleniumUtils.createChromeDriver();
        try {
            eyes.open(driver, "Eyes SDK", "UFG Flow");
            driver.get("http://applitools.github.io/demo");
            eyes.checkWindow();
            eyes.closeAsync();
        } finally {
            eyes.abortAsync();
            driver.quit();
            runner.getAllTestResults(false);
        }

        Assert.assertNull(errorMessage.get(), errorMessage.get());
    }

    @Test
    public void testRunnerConcurrency() {
        final VisualGridRunner runner = spy(new VisualGridRunner(1));

        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        final AtomicBoolean isFail = new AtomicBoolean(false);
        ServerConnector serverConnector = new MockServerConnector() {
            @Override
            public void startSession(final TaskListener<RunningSession> listener, SessionStartInfo sessionStartInfo) {
                if (openCounter.get() != closeCounter.get()) {
                    isFail.set(true);
                }

                openCounter.incrementAndGet();
                super.startSession(listener, sessionStartInfo);
            }

            @Override
            public void stopSession(final TaskListener<TestResults> listener, RunningSession runningSession, boolean isAborted, boolean save) {
                closeCounter.incrementAndGet();
                super.stopSession(listener, runningSession, isAborted, save);
            }
        };

        VisualGridEyes eyes = new VisualGridEyes(runner, configurationProvider);
        eyes.setLogHandler(new StdoutLogHandler());
        eyes.setServerConnector(serverConnector);
        configuration.addBrowser(new IosDeviceInfo(IosDeviceName.iPhone_7));
        configuration.addDeviceEmulation(DeviceName.Galaxy_S5, ScreenOrientation.LANDSCAPE);
        WebDriver driver = SeleniumUtils.createChromeDriver();
        try {
            eyes.open(driver, "Eyes SDK", "UFG Runner Concurrency", new RectangleSize(800, 800));
            driver.get("http://applitools.github.io/demo");
            eyes.check(Target.window().fully());
            eyes.closeAsync();
        } finally {
            eyes.abortAsync();
            driver.quit();
            runner.getAllTestResults(false);
        }

        Assert.assertFalse(isFail.get(), "Second test started before first test finished");
    }

    @Test
    public void testServerConcurrency() {
        final VisualGridRunner runner = new VisualGridRunner(5);

        final AtomicInteger counter = new AtomicInteger(0);
        ServerConnector serverConnector = new MockServerConnector() {
            @Override
            public void startSession(final TaskListener<RunningSession> listener, SessionStartInfo sessionStartInfo) {
                if (counter.getAndIncrement() < 3) {
                    final RunningSession newSession = new RunningSession();
                    newSession.setConcurrencyFull(true);
                    listener.onComplete(newSession);
                    return;
                }

                // Return a valid response from the server
                super.startSession(listener, sessionStartInfo);
            }
        };

        VisualGridEyes eyes = spy(new VisualGridEyes(runner, configurationProvider));
        final AtomicBoolean wasConcurrencyFull = new AtomicBoolean(false);
        doAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                Boolean result = (Boolean) invocation.callRealMethod();
                if (result) {
                    wasConcurrencyFull.set(true);
                }

                return result;
            }
        }).when(eyes).isConcurrencyFull();

        eyes.setLogHandler(new StdoutLogHandler());
        eyes.setServerConnector(serverConnector);
        configuration.addBrowser(new IosDeviceInfo(IosDeviceName.iPhone_7));
        WebDriver driver = SeleniumUtils.createChromeDriver();
        try {
            eyes.open(driver, "Eyes SDK", "UFG Server Concurrency", new RectangleSize(800, 800));
            driver.get("http://applitools.github.io/demo");
            eyes.check(Target.window().fully());
            eyes.closeAsync();
        } finally {
            eyes.abortAsync();
            driver.quit();
            runner.getAllTestResults(false);
        }

        Assert.assertTrue(wasConcurrencyFull.get());
        Assert.assertEquals(counter.get(), 4);
    }
}
