/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

open module AnnotationToolsNbLayer {
    
    exports com.mastfrog.annotation.nblayer;
    
    requires java.compiler;
    
    requires com.mastfrog.java.vogon;

    requires com.mastfrog.annotation.tools;
    requires com.mastfrog.function;
    requires com.mastfrog.preconditions;
    
    // derived from org.netbeans.api/org-netbeans-api-annotations-common-0.0.0-?:provided in org/netbeans/api/org-netbeans-api-annotations-common/RELEASE126/org-netbeans-api-annotations-common-RELEASE126.pom
    requires org.netbeans.api.annotations.common.RELEASE126;

    // derived from org.netbeans.api/org-openide-filesystems-0.0.0-?:provided in org/netbeans/api/org-openide-filesystems/RELEASE126/org-openide-filesystems-RELEASE126.pom
    requires org.openide.filesystems.RELEASE126;

    // derived from org.netbeans.api/org-openide-util-lookup-0.0.0-?:provided in org/netbeans/api/org-openide-util-lookup/RELEASE126/org-openide-util-lookup-RELEASE126.pom
    requires org.openide.util.lookup.RELEASE126;
}
