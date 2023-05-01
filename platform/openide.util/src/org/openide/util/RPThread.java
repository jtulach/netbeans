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

interface RPThread {
    static RPThread obtain() {
        return RPThreadImpl.obtain();
    }

    static RPThread findFor(Thread t) {
        if (t instanceof RPThread) {
            return (RPThread) t;
        }
        return null;
    }


    void setName(String name);
    Object getName();
    void attachTo(RequestProcessor aThis);
    void setContextClassLoader(ClassLoader ctxLoader);
    ClassLoader getContextClassLoader();
    void interrupt();
    RequestProcessor processing();
    void inactivate(String debug);
    boolean isAlive();
    boolean belongsTo(RequestProcessor aThis);
    void join(long remaining) throws InterruptedException;
    /** Interrupts.
     *
     * @param task the task to interrupt
     * @param interrupt if {@code null} then value of {@code rp.interruptTask} is used
     * @param rp request processor
     * @return boolean if interrupted
     */
    boolean interrupt(RequestProcessor.Task task, Boolean interrupt, RequestProcessor rp);
    void doEvaluate(RequestProcessor.Task aThis, Object processorLock, RequestProcessor aThis0);
}
