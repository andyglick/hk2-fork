/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
/*
 * DirectoryBasedRepository.java
 *
 * Created on October 17, 2006, 2:14 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package com.sun.enterprise.module.common_impl;

import com.sun.enterprise.module.ModuleDefinition;
import com.sun.enterprise.module.RepositoryChangeListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This class is a directory based repository implementation. This mean that all jar
 * file residing a particular directory will be elligible to be added to this
 * repository instance. Jar File will need to be compliant with the module definition
 * spec which mean have a manifest file with the correct elements OR a separate
 * manifest file with the same file name with a .mf extension.
 *
 * @author Jerome Dochez
 */
public class DirectoryBasedRepository extends AbstractRepositoryImpl {
    
    private final File repository;
    private final int intervalInMs = Integer.getInteger("hk2.file.directory.changeIntervalTimer", 10);
    private Timer timer;
    private boolean isTimerThreadDaemon = false;

    /** Creates a new instance of DirectoryBasedRepository */
    public DirectoryBasedRepository(String name, File repository) {
        super(name,repository.toURI());
        this.repository = repository;
    }


    public DirectoryBasedRepository(String name, File repository, boolean isTimerThreadDaemon) {
        this(name, repository);
        this.isTimerThreadDaemon = isTimerThreadDaemon;
    }

    @Override
    public synchronized boolean addListener(RepositoryChangeListener listener) {

        final boolean returnValue = super.addListener(listener);
        if (returnValue && timer==null) {
            timer = new Timer("hk2-repo-listener-"+ this.getName(), isTimerThreadDaemon);
            timer.schedule(new TimerTask() {
                long lastModified = repository.lastModified();
                public void run() {
                    synchronized(this) {
                        if (lastModified<repository.lastModified()) {
                            lastModified = repository.lastModified();
                            // something has changed, look into this...
                            directoryChanged();
                        }
                    }
                }
            }, intervalInMs, intervalInMs);
            timer.purge();
        }
        return returnValue;        
    }

    @Override
    public void shutdown() throws IOException {
        if (timer!=null) {
            timer.cancel();
            timer = null;
        }
    }
    

    protected void loadModuleDefs(Map<String, ModuleDefinition> moduleDefs, List<URI> libraries) throws IOException {
        if (!repository.exists()) {
            throw new FileNotFoundException(repository.getAbsolutePath());
        }


        try {
            File[] files = repository.listFiles();
            for (File aFile : files) {
                if (aFile.getName().endsWith(".jar") && !isDisabled(aFile)) {
                    ModuleDefinition moduleDef = loadJar(aFile);
                    if (moduleDef!=null) {
                        moduleDefs.put(moduleDef.getName(), moduleDef);
                    } else {
                        libraries.add(aFile.toURI());
                    }
                }
            }

        } catch (IOException e) {
            IOException x = new IOException("Failed to load modules from " + repository);
            x.initCause(e);
            throw x;
        }
    }

    /**
     * Checks the <tt>xyz.disabled</tt> file for <tt>xyz.jar</tt> and return true
     * if the file exists.
     */
    private boolean isDisabled(File jar) {
        String fileName = jar.getName();
        fileName = fileName.substring(0,fileName.lastIndexOf('.'))+".disabled";
        File disabledFile = new File(jar.getParent(),fileName);
        return disabledFile.exists();
    }

    private synchronized void directoryChanged() {

        // not the most efficient implementation, could be revisited later
        HashMap<String, ModuleDefinition> newModuleDefs = new HashMap<String, ModuleDefinition>();
        List<URI> libraries = new ArrayList<URI>();

        try {
            loadModuleDefs(newModuleDefs, libraries);
        } catch(IOException ioe) {
            // we probably need to wait until the jar has finished being copied
            // XXX add some form of retry
        }
        for(ModuleDefinition def : newModuleDefs.values()) {
            if (find(def.getName(), def.getVersion())==null) {
                add(def);
                for (RepositoryChangeListener listener : listeners) {
                    listener.moduleAdded(def);
                }
            }
        }
        for (ModuleDefinition def : findAll()) {
            if (!newModuleDefs.containsKey(def.getName())) {
                remove(def);
                for (RepositoryChangeListener listener : listeners) {
                    listener.moduleRemoved(def);
                }
            }
        }
        List<URI> originalLibraries = super.getJarLocations();
        for (URI location : libraries) {
            if (!originalLibraries.contains(location)) {
                addLibrary(location);
                for (RepositoryChangeListener listener : listeners) {
                    listener.jarAdded(location);
                }
            }
        }
        if (originalLibraries.size()>0) {
            List<URI> copy = new ArrayList<URI>(originalLibraries.size());
            copy.addAll(originalLibraries);
            for (URI originalLocation : copy) {
                if (!libraries.contains(originalLocation)) {
                    removeLibrary(originalLocation);
                    for (RepositoryChangeListener listener : listeners) {
                        listener.jarRemoved(originalLocation);
                    }
                }
            }
        }
    }

}
