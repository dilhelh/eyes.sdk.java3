package com.applitools.eyes.visualgrid.services;

import com.applitools.eyes.Logger;
import com.applitools.eyes.TestResultContainer;
import com.applitools.utils.GeneralUtils;

import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

public class OpenerService extends EyesService {

    private final AtomicInteger currentTestAmount = new AtomicInteger();
    private final Object concurrencyLock;

    public OpenerService(String serviceName, ThreadGroup servicesGroup, Logger logger, int testsPoolSize, Object openerServiceLock, EyesServiceListener listener, Tasker tasker) {
        super(serviceName, servicesGroup, logger, testsPoolSize, listener, tasker);
        this.concurrencyLock = openerServiceLock;
    }

    void runNextTask() {
        if (!isServiceOn) {
            return;
        }

        if (this.eyesConcurrency > currentTestAmount.get()) {
            final FutureTask<TestResultContainer> task = this.listener.getNextTask(tasker);
            if (task != null) {
                this.currentTestAmount.incrementAndGet();
                logger.verbose("open concurrent sessions: " + currentTestAmount);
                this.executor.submit(task);
            }
        } else {
            synchronized (concurrencyLock) {
                try {
                    logger.verbose("Waiting for concurrency to be free");
                    concurrencyLock.wait();
                    logger.verbose("concurrency free");
                } catch (InterruptedException e) {
                    GeneralUtils.logExceptionStackTrace(logger ,e);
                }
            }
        }
    }

    @Override
    void stopService() {
        logger.verbose("concurrency on stop = "+this.currentTestAmount);
        super.stopService();
    }

    public synchronized int decrementConcurrency(){
        return this.currentTestAmount.decrementAndGet();
    }
}
