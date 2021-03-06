/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 * 
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright (C) 2010-2014 Martin
 */
package com.googlecode.lanterna.gui2;

import java.io.EOFException;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Default implementation of TextGUIThread
 * @author Martin
 */
class DefaultTextGUIThread implements TextGUIThread {
    private final TextGUI textGUI;
    private final Queue<Runnable> customTasks;
    private Status status;
    private Thread textGUIThread;
    private CountDownLatch waitLatch;
    private ExceptionHandler exceptionHandler;

    DefaultTextGUIThread(TextGUI textGUI) {
        this.textGUI = textGUI;
        this.customTasks = new LinkedBlockingQueue<Runnable>();
        this.status = Status.CREATED;
        this.waitLatch = new CountDownLatch(0);
        this.textGUIThread = null;
        this.exceptionHandler = new ExceptionHandler() {
            @Override
            public boolean onIOException(IOException e) {
                e.printStackTrace();
                return true;
            }

            @Override
            public boolean onRuntimeException(RuntimeException e) {
                e.printStackTrace();
                return true;
            }
        };
    }

    @Override
    public void start() throws IllegalStateException {
        if(status == Status.STARTED) {
            throw new IllegalStateException("TextGUIThread is already started");
        }

        textGUIThread = new Thread("LanternaGUI") {
            @Override
            public void run() {
                mainGUILoop();
            }
        };
        textGUIThread.start();
        status = Status.STARTED;
        this.waitLatch = new CountDownLatch(1);
    }

    @Override
    public void stop() {
        if(status != Status.STARTED) {
            return;
        }

        status = Status.STOPPING;
    }

    @Override
    public void waitForStop() throws InterruptedException {
        waitLatch.await();
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public void invokeLater(Runnable runnable) throws IllegalStateException {
        if(status != Status.STARTED) {
            throw new IllegalStateException("Cannot schedule " + runnable + " for execution on the TextGUIThread " +
                    "because the thread is in " + status + " state");
        }
        if(Thread.currentThread() == textGUIThread) {
            runnable.run();
        }
        else {
            customTasks.add(runnable);
        }
    }

    @Override
    public void invokeAndWait(final Runnable runnable) throws IllegalStateException, InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        invokeLater(new Runnable() {
            @Override
            public void run() {
                runnable.run();
                countDownLatch.countDown();
            }
        });
        countDownLatch.await();
    }

    @Override
    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        if(exceptionHandler == null) {
            throw new IllegalArgumentException("Cannot call setExceptionHandler(null)");
        }
        this.exceptionHandler = exceptionHandler;
    }

    private void mainGUILoop() {
        try {
            //Draw initial screen, after this only draw when the GUI is marked as invalid
            try {
                textGUI.updateScreen();
            }
            catch(IOException e) {
                exceptionHandler.onIOException(e);
            }
            catch(RuntimeException e) {
                exceptionHandler.onRuntimeException(e);
            }
            while(status == Status.STARTED) {
                try {
                    textGUI.processInput();
                }
                catch(EOFException e) {
                    stop();
                    break; //Break out quickly from the main loop
                }
                catch(IOException e) {
                    if(exceptionHandler.onIOException(e)) {
                        stop();
                        break;
                    }
                }
                catch(RuntimeException e) {
                    if(exceptionHandler.onRuntimeException(e)) {
                        stop();
                        break;
                    }
                }
                while(!customTasks.isEmpty()) {
                    Runnable r = customTasks.poll();
                    if(r != null) {
                        r.run();
                    }
                }
                if(textGUI.isPendingUpdate()) {
                    try {
                        textGUI.updateScreen();
                    }
                    catch(IOException e) {
                        if(exceptionHandler.onIOException(e)) {
                            stop();
                            break;
                        }
                    }
                    catch(RuntimeException e) {
                        if(exceptionHandler.onRuntimeException(e)) {
                            stop();
                            break;
                        }
                    }
                }
                else {
                    try {
                        Thread.sleep(1);
                    }
                    catch(InterruptedException ignored) {}
                }
            }
        }
        finally {
            status = Status.STOPPED;
            waitLatch.countDown();
        }
    }
}
