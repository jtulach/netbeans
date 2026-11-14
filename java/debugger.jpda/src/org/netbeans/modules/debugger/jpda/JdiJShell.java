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
package org.netbeans.modules.debugger.jpda;
import com.sun.jdi.*;
import jdk.jshell.JShell;
import jdk.jshell.execution.StreamingExecutionControl;
import jdk.jshell.spi.*;
import java.io.*;
import java.net.*;
import java.util.*;
import jshattach.JShellAgent;

public final class JdiJShell {
    private static final String AGENT = JShellAgent.class.getName();
    private static final int OPTS = ObjectReference.INVOKE_SINGLE_THREADED;

    private JdiJShell() {}

    /** Picks any thread that is suspended by an event (breakpoint, step, ...). */
    public static JShell open(VirtualMachine vm) throws Exception {
        for (ThreadReference t : vm.allThreads()) {
            if (!t.isSuspended()) continue;
            try {
                return open(vm, t);
            } catch (IncompatibleThreadStateException notAtEvent) {
                // suspended, but not by an event; try the next one
            }
        }
        throw new IllegalStateException("No thread is stopped at a breakpoint/step; suspend the target first");
    }

    /** @param thread a thread suspended by a JDI event, e.g. BreakpointEvent.thread() */
    public static JShell open(VirtualMachine vm, ThreadReference thread) throws Exception {
        ClassType loaderType = type(vm, "java.lang.ClassLoader");
        ObjectReference sysLoader = (ObjectReference) loaderType.invokeMethod(thread,
                method(loaderType, "getSystemClassLoader", "()Ljava/lang/ClassLoader;"), List.of(), OPTS);

        ClassType agent = defineAgent(vm, thread, loaderType, sysLoader);

        int port = ((IntegerValue) agent.invokeMethod(thread,
                method(agent, "start", "()I"), List.of(), OPTS)).value();

        // If the VM is suspend-all, the new thread starts suspended too. Let only it run.
        for (ThreadReference t : vm.allThreads()) {
            if (t.name().equals("jshell-agent-" + port)) {
                while (t.suspendCount() > 0) t.resume();
            }
        }

        String classPath = systemProperty(vm, thread, "java.class.path");

        ExecutionControlProvider provider = new ExecutionControlProvider() {
            @Override public String name() { return "jdi-attached"; }

            @Override
            public ExecutionControl generate(ExecutionEnv env, Map<String, String> params) throws IOException {
                Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                return new StreamingExecutionControl(out, in) {
                    @Override public void close() {
                        try { super.close(); } finally {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    }
                };
            }
        };

        return JShell.builder()
                .executionEngine(provider, Map.of())
                .compilerOptions("--class-path", classPath)   // compile-time view of the app's classes
                .build();
    }

    // ---- helpers ----

    private static ClassType defineAgent(VirtualMachine vm, ThreadReference thread,
                                         ClassType loaderType, ObjectReference loader) throws Exception {
        for (ReferenceType rt : vm.classesByName(AGENT)) {
            if (loader.equals(rt.classLoader())) return prepared(vm, thread, rt, loader);
        }
        byte[] bytes;
        try (InputStream in = JShellAgent.class.getResourceAsStream("JShellAgent.class")) {
            bytes = in.readAllBytes();
        }
        ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
        ArrayReference arr = byteArrayType.newInstance(bytes.length);
        StringReference name = vm.mirrorOf(AGENT);
        arr.disableCollection();
        name.disableCollection();
        try {
            List<Value> values = new ArrayList<>(bytes.length);
            for (byte b : bytes) values.add(vm.mirrorOf(b));
            arr.setValues(values);
            ClassObjectReference cls = (ClassObjectReference) loader.invokeMethod(thread,
                    method(loaderType, "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;"),
                    List.of(name, arr, vm.mirrorOf(0), vm.mirrorOf(bytes.length)), OPTS);
            return prepared(vm, thread, cls.reflectedType(), loader);
        } finally {
            arr.enableCollection();
            name.enableCollection();
        }
    }

    private static String systemProperty(VirtualMachine vm, ThreadReference thread, String key) throws Exception {
        ClassType system = type(vm, "java.lang.System");
        StringReference k = vm.mirrorOf(key);
        k.disableCollection();
        try {
            Value v = system.invokeMethod(thread,
                    method(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;"), List.of(k), OPTS);
            return v == null ? "" : ((StringReference) v).value();
        } finally {
            k.enableCollection();
        }
    }

    private static ClassType type(VirtualMachine vm, String name) {
        return (ClassType) vm.classesByName(name).get(0);
    }

    private static Method method(ClassType type, String name, String signature) {
        Method m = type.concreteMethodByName(name, signature);
        if (m == null) throw new IllegalStateException("Not found: " + type.name() + "." + name + signature);
        return m;
    }

    private static ClassType prepared(VirtualMachine vm, ThreadReference thread,
                                      ReferenceType rt, ObjectReference loader) throws Exception {
        if (!rt.isPrepared()) {
            ClassType classType = type(vm, "java.lang.Class");
            StringReference n = vm.mirrorOf(rt.name());
            n.disableCollection();
            try {
                classType.invokeMethod(thread,
                        method(classType, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
                        List.of(n, vm.mirrorOf(true), loader), OPTS);   // true = link + initialize
            } finally {
                n.enableCollection();
            }
        }
        return (ClassType) rt;
    }    
}    
