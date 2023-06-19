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
package org.netbeans.api.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;
import org.netbeans.spi.lsp.DocumentSelectorRegistration;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

@DocumentSelectorRegistration(language = "strange", pattern = "**/*.strange")
public class DocumentSelectorTest {
    @Test
    public void loadAllPatterns() {
      FileObject fo = FileUtil.getConfigFile("DocSel/DocumentSelectorTest.instance");
      assertNotNull("Config file generated", fo);
      assertEquals("strange", fo.getAttribute("language"));
      assertEquals("**/*.strange", fo.getAttribute("pattern"));
    }
}
