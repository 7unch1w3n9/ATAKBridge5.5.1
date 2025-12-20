package com.atakmap.android.LoRaBridge.JNI;

import com.atakmap.coremap.log.Log;

import android.hardware.usb.UsbDeviceConnection;
import android.os.ParcelFileDescriptor;

import java.util.concurrent.*;

public final class FlowgraphEngine {
    private static final String TAG = "FlowgraphEngine";
    private static final FlowgraphEngine I = new FlowgraphEngine();
    public static FlowgraphEngine get() { return I; }

    private final Object lock = new Object();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowgraphThread");
        t.setDaemon(true);
        return t;
    });

    private Future<?> task;
    private enum State { STOPPED, STARTING, RUNNING, STOPPING }
    private volatile State state = State.STOPPED;
    private UsbDeviceConnection heldConn;
    private volatile CountDownLatch terminated;

    public boolean isBusy() {
        synchronized (lock) {
            return state == State.STARTING || state == State.RUNNING || state == State.STOPPING;
        }
    }

    public void startWithConnection(UsbDeviceConnection conn) {
        synchronized (lock) {
            if (isBusy()) {
                Log.w(TAG, "start ignored: state=" + state);
                if (conn != null) conn.close();
                return;
            }
            heldConn = conn;
            state = State.STARTING;

            final int rawFd = safeFd(conn);
            Log.i(TAG, "start enter fd=" + rawFd);

            terminated = new CountDownLatch(1);

            task = exec.submit(() -> {
                int fdForNative = -1;
                ParcelFileDescriptor pfd = null;

                try {
                    synchronized (lock) {
                        state = State.RUNNING;
                    }

                    // Duplicate file descriptor
                    pfd = ParcelFileDescriptor.fromFd(rawFd);
                    fdForNative = pfd.getFd();

                    // Close original connection immediately after duplication
                    if (conn != null) {
                        conn.close();
                        heldConn = null;
                        Log.i(TAG, "Closed original USB connection after FD duplication");
                    }

                    // This call blocks until flowgraph terminates
                    Log.i(TAG, "run_flowgraph_with_fd(" + fdForNative + ") begin");
                    int rc = FlowgraphNative.run_flowgraph_with_fd(fdForNative);
                    Log.i(TAG, "run_flowgraph_with_fd exit rc=" + rc);

                } catch (Throwable t) {
                    Log.e(TAG, "Flowgraph thread crashed", t);
                } finally {
                    // Close duplicated file descriptor
                    if (pfd != null) {
                        try {
                            pfd.close();
                            Log.i(TAG, "Closed duplicated file descriptor");
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing ParcelFileDescriptor", e);
                        }
                    }

                    // Close USB connection if still held
                    if (heldConn != null) {
                        try {
                            heldConn.close();
                            Log.i(TAG, "Closed USB connection in finally block");
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing USB connection", e);
                        }
                        heldConn = null;
                    }

                    // Signal termination
                    terminated.countDown();
                    Log.i(TAG, "Flowgraph thread terminated, latch released");
                }
            });
        }
    }

    public void stop() {
        Log.i(TAG, "stop() called, state=" + state);

        synchronized (lock) {
            if (state == State.STOPPED) {
                Log.i(TAG, "stop() ignored: already STOPPED");
                return;
            }
            state = State.STOPPING;
        }

        try {
            // Request native flowgraph shutdown
            Log.i(TAG, "Calling FlowgraphNative.shutdown()");
            FlowgraphNative.shutdown();
            Log.i(TAG, "FlowgraphNative.shutdown() returned");

            // Wait for thread to terminate
            CountDownLatch done = terminated;
            if (done != null) {
                Log.i(TAG, "Waiting for flowgraph thread to terminate...");
                boolean finished = done.await(5, TimeUnit.SECONDS);
                if (finished) {
                    Log.i(TAG, "Flowgraph thread terminated successfully");
                } else {
                    Log.w(TAG, "Flowgraph termination timeout after 5 seconds");
                }
            } else {
                Log.w(TAG, "Termination latch is null");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "stop() interrupted", e);
        } finally {
            Future<?> f;
            synchronized (lock) {
                f = task;
                task = null;
                state = State.STOPPED;
            }

            // Cancel task if still running
            if (f != null && !f.isDone()) {
                Log.w(TAG, "Cancelling flowgraph task");
                f.cancel(true);
            }

            Log.i(TAG, "stop() complete");
        }
    }

    private static int safeFd(UsbDeviceConnection c) {
        if (c == null) return -1;
        try {
            return c.getFileDescriptor();
        } catch (Throwable t) {
            return -2;
        }
    }
}