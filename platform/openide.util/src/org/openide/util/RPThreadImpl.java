/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.openide.util;

import java.util.Collections;
import java.util.Map;
import java.util.Stack;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.util.lookup.Lookups;

//------------------------------------------------------------------------------

// The Processor management implImplementation
//------------------------------------------------------------------------------
/**
/** A special thread that processes timouted Tasks from a RequestProcessor.
 * It uses the RequestProcessor as a synchronized queue (a Channel),
 * so it is possible to run more Processors in paralel for one RequestProcessor
 */
final class RPThreadImpl extends Thread implements RPThread {
    /** A stack containing all the inactive Processors */
    private static final Stack<RPThreadImpl> POOL = new Stack<>();
    /* One minute of inactivity and the Thread will die if not assigned */
    private static final int INACTIVE_TIMEOUT = Integer.getInteger("org.openide.util.RequestProcessor.inactiveTime", 60000); // NOI18N

    /** Internal variable holding the Runnable to be run.
     * Used for passing Runnable through Thread boundaries.
     */
    //private Item task;
    private RequestProcessor source;
    /** task we are working on */
    private RequestProcessor.Task todo;
    private boolean idle = true;
    /** Waiting lock */
    private final Object lock = new Object();
    RequestProcessor processing;

    RPThreadImpl() {
        super(RequestProcessor.TOP_GROUP.getTopLevelThreadGroup(), "Inactive RequestProcessor thread"); // NOI18N
        setDaemon(true);
        assert !Thread.holdsLock(POOL); // new Thread may lead to huge classloading
    } // NOI18N
    // new Thread may lead to huge classloading

    /** Provide an inactive Processor instance. It will return either
     * existing inactive processor from the pool or will create a new instance
     * if no instance is in the pool.
     *
     * @return inactive Processor
     */
    static RPThreadImpl obtain() {
        RPThreadImpl newP = null;
        for (;;) {
            synchronized (POOL) {
                if (POOL.isEmpty()) {
                    if (newP != null) {
                        RPThreadImpl proc = newP;
                        proc.idle = false;
                        proc.start();
                        return proc;
                    }
                } else {
                    assert checkAccess(RequestProcessor.TOP_GROUP.getTopLevelThreadGroup());
                    RPThreadImpl proc = POOL.pop();
                    proc.idle = false;
                    return proc;
                }
            }
            newP = new RPThreadImpl();
        }
    }

    static RPThreadImpl findFor(Thread c) {
        return c instanceof RPThreadImpl ? (RPThreadImpl)c : null;
    }

    private static boolean checkAccess(ThreadGroup g) throws SecurityException {
        g.checkAccess();
        return true;
    }

    @Override
    public RequestProcessor processing() {
        return processing;
    }

    /** A way of returning a Processor to the inactive pool.
     *
     * @param proc the Processor to return to the pool. It shall be inactive.
     * @param last the debugging string identifying the last client.
     */
    public void inactivate(String last) {
        synchronized (POOL) {
            setName("Inactive RequestProcessor thread [Was:" + getName() + "/" + last + "]"); // NOI18N
            idle = true;
            POOL.push(this);
        }
    }

    /** setPriority wrapper that skips setting the same priority
     * we'return already running at */
    private void setPrio(int priority) {
        if (priority != getPriority()) {
            setPriority(priority);
        }
    }

    /**
     * Sets an Item to be performed and notifies the performing Thread
     * to start the processing.
     *
     * @param r the Item to run.
     */
    public void attachTo(RequestProcessor src) {
        synchronized (lock) {
            //assert(source == null);
            source = src;
            lock.notify();
        }
    }

    public boolean belongsTo(RequestProcessor r) {
        synchronized (lock) {
            return source == r;
        }
    }

    /**
     * The method that will repeatedly wait for a request and perform it.
     */
    @Override
    public void run() {
        for (;;) {
            RequestProcessor current = null;
            synchronized (lock) {
                try {
                    if (source == null) {
                        lock.wait(INACTIVE_TIMEOUT); // wait for the job
                    }
                } catch (InterruptedException e) {
                }
                // not interesting
                current = source;
                source = null;
                if (current == null) {
                    // We've timeouted
                    synchronized (POOL) {
                        if (idle) {
                            // and we're idle
                            POOL.remove(this);
                            break; // exit the thread
                        } else {
                            // this will happen if we've been just
                            continue; // before timeout when we were assigned
                        }
                    }
                }
            }
            String debug = null;
            Logger em = RequestProcessor.logger();
            boolean loggable = em.isLoggable(Level.FINE);
            if (loggable) {
                try {
                    processing = current;
                    em.log(Level.FINE, "Begining work {0}", getName()); // NOI18N
                } finally {
                    processing = null;
                }
            }
            // while we have something to do
            for (;;) {
                Lookup[] lkp = new Lookup[1];
                // need the same sync as interruptTask
                synchronized (current.processorLock) {
                    todo = current.askForWork(this, debug, lkp);
                    if (todo == null) {
                        break;
                    }
                }
                setPrio(todo.getPriority());
                try {
                    processing = current;
                    if (loggable) {
                        em.log(Level.FINE, "  Executing {0}", todo); // NOI18N
                    }
                    registerParallel(todo, current);
                    Lookups.executeWith(lkp[0], todo);
                    lkp[0] = null;
                    if (loggable) {
                        em.log(Level.FINE, "  Execution finished in {0}", getName()); // NOI18N
                    }
                    debug = todo.debug();
                } catch (OutOfMemoryError oome) {
                    // direct notification, there may be no room for
                    // annotations and we need OOME to be processed
                    // for debugging hooks
                    em.log(Level.SEVERE, null, oome);
                } catch (StackOverflowError e) {
                    // recoverable too
                    doNotify(todo, e);
                } catch (ThreadDeath t) {
                    // #201098: ignore
                } catch (Throwable t) {
                    doNotify(todo, t);
                } finally {
                    processing = null;
                    unregisterParallel(todo, current);
                }
                // need the same sync as interruptTask
                synchronized (current.processorLock) {
                    // to improve GC
                    todo = null;
                    // and to clear any possible interrupted state
                    // set by calling Task.cancel ()
                    Thread.interrupted();
                }
            }
            if (loggable) {
                try {
                    processing = current;
                    em.log(Level.FINE, "Work finished {0}", getName()); // NOI18N
                } finally {
                    processing = null;
                }
            }
        }
    }

    /** Evaluates given task directly.
     */
    public final void doEvaluate(RequestProcessor.Task t, Object processorLock, RequestProcessor src) {
        RequestProcessor.Task previous = todo;
        boolean interrupted = Thread.interrupted();
        try {
            todo = t;
            t.run();
        } finally {
            synchronized (processorLock) {
                todo = previous;
                if (interrupted || todo.item == null) {
                    if (src.interruptThread) {
                        // reinterrupt the thread if it was interrupted and
                        // we support interrupts
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /** Called under the processorLock */
    public void interruptTask(RequestProcessor.Task t, RequestProcessor src) {
        if (t != todo) {
            // not running this task so
            return;
        }
        if (src.interruptThread) {
            // otherwise interrupt this thread
            interrupt();
        }
    }

    public boolean interrupt(RequestProcessor.Task t, RequestProcessor src) {
        if (t != todo) {
            return false;
        }
        interrupt();
        return true;
    }

    /** See #20467. */
    private static void doNotify(RequestProcessor.Task todo, Throwable ex) {
        if (RequestProcessor.SLOW) {
            RequestProcessor.Item item = todo.item;
            if (item != null && item.message == null) {
                item.message = ex.toString();
                item.initCause(ex);
                ex = item;
            }
        }
        RequestProcessor.logger().log(Level.SEVERE, "Error in RequestProcessor " + todo.debug(), ex);
    }
    private static final Map<Class<? extends Runnable>, Object> warnedClasses = Collections.synchronizedMap(new WeakHashMap<Class<? extends Runnable>, Object>());

    private void registerParallel(RequestProcessor.Task todo, RequestProcessor rp) {
        if (rp.warnParallel == 0 || todo.run == null) {
            return;
        }
        final Class<? extends Runnable> c = todo.run.getClass();
        AtomicInteger number;
        synchronized (rp.processorLock) {
            if (rp.inParallel == null) {
                rp.inParallel = new WeakHashMap<Class<? extends Runnable>, AtomicInteger>();
            }
            number = rp.inParallel.get(c);
            if (number == null) {
                rp.inParallel.put(c, number = new AtomicInteger(1));
            } else {
                number.incrementAndGet();
            }
        }
        if (number.get() >= rp.warnParallel && warnedClasses.put(c, "") == null) {
            final String msg = "Too many " + c.getName() + " (" + number + ") in shared RequestProcessor; create your own"; // NOI18N
            Exception ex = null;
            RequestProcessor.Item itm = todo.item;
            if (itm != null) {
                ex = new IllegalStateException(msg);
                ex.setStackTrace(itm.getStackTrace());
            }
            RequestProcessor.logger().log(Level.WARNING, msg, ex);
        }
    }

    private void unregisterParallel(RequestProcessor.Task todo, RequestProcessor rp) {
        if (rp.warnParallel == 0 || todo.run == null) {
            return;
        }
        synchronized (rp.processorLock) {
            Class<? extends Runnable> c = todo.run.getClass();
            rp.inParallel.get(c).decrementAndGet();
        }
    }

}
