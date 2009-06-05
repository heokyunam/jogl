/*
 * Copyright (c) 2008 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 */

package com.sun.opengl.impl.egl;

import javax.media.nativewindow.*;
import javax.media.nativewindow.egl.*;
import com.sun.nativewindow.impl.*;

import javax.media.opengl.*;
import com.sun.opengl.impl.*;

/** Subclass of GraphicsConfigurationFactory used when non-AWT tookits
    are used on X11 platforms. Toolkits will likely need to delegate
    to this one to change the accepted and returned types of the
    GraphicsDevice and GraphicsConfiguration abstractions. */

public class EGLGraphicsConfigurationFactory extends GraphicsConfigurationFactory {
    // Keep this under the same debug flag as the drawable factory for convenience
    protected static final boolean DEBUG = com.sun.opengl.impl.Debug.debug("EGLDrawableFactory");

    public EGLGraphicsConfigurationFactory() {
        // become the selector for KD/EGL ..
        GraphicsConfigurationFactory.registerFactory(javax.media.nativewindow.egl.EGLGraphicsDevice.class, this);
    }

    public AbstractGraphicsConfiguration chooseGraphicsConfiguration(Capabilities capabilities,
                                                                     CapabilitiesChooser chooser,
                                                                     AbstractGraphicsScreen absScreen) {
        if (absScreen == null) {
            throw new IllegalArgumentException("This NativeWindowFactory accepts only AbstractGraphicsDevice objects");
        }

        if (capabilities != null &&
            !(capabilities instanceof GLCapabilities)) {
            throw new IllegalArgumentException("This NativeWindowFactory accepts only GLCapabilities objects");
        }

        if (chooser != null &&
            !(chooser instanceof GLCapabilitiesChooser)) {
            throw new IllegalArgumentException("This NativeWindowFactory accepts only GLCapabilitiesChooser objects");
        }

        if (chooser == null) {
            chooser = new DefaultGLCapabilitiesChooser();
        }

        return chooseGraphicsConfigurationStatic((GLCapabilities) capabilities,
                                                 (GLCapabilitiesChooser) chooser,
                                                 absScreen);
    }

    public static EGLGraphicsConfiguration chooseGraphicsConfigurationStatic(GLCapabilities capabilities,
                                                                             GLCapabilitiesChooser chooser,
                                                                             AbstractGraphicsScreen absScreen) {
        if (capabilities == null) {
            capabilities = new GLCapabilities(null);
        }
        GLProfile glp = capabilities.getGLProfile();

        if(null==absScreen) {
            throw new GLException("Null AbstractGraphicsScreen");
        }
        AbstractGraphicsDevice absDevice = absScreen.getDevice();

        if(null==absDevice || !(absDevice instanceof EGLGraphicsDevice)) {
            throw new GLException("GraphicsDevice must be a valid EGLGraphicsDevice");
        }
        long eglDisplay = absDevice.getHandle();

        if (eglDisplay == EGL.EGL_NO_DISPLAY) {
            throw new GLException("Invalid EGL display: "+absDevice);
        }

        EGLGraphicsConfiguration res = eglChooseConfig(eglDisplay, capabilities, absScreen);
        if(null!=res) {
            return res;
        }
        if(DEBUG) {
            System.err.println("eglChooseConfig failed with given capabilities");
        }

        _EGLConfig[] configs = new _EGLConfig[10];
        int[] numConfigs = new int[1];

        if(!EGL.eglGetConfigs(eglDisplay, configs, 10, numConfigs, 0)) {
            throw new GLException("Graphics configuration fetch (eglGetConfigs) failed");
        }
        if (numConfigs[0] == 0) {
            throw new GLException("Graphics configuration fetch (eglGetConfigs) - no EGLConfig found");
        }
        GLCapabilities[] caps = new GLCapabilities[numConfigs[0]];
        for(int i=0; i<caps.length; i++) {
            caps[i] = EGLGraphicsConfiguration.EGLConfig2Capabilities(glp, eglDisplay, configs[i]);
            if(DEBUG) {
                System.err.println("caps["+i+"] "+caps[i]);
            }
        }
        int chosen = -1;
        try {
            chosen = chooser.chooseCapabilities(capabilities, caps, -1);
        } catch (NativeWindowException e) { throw new GLException(e); }
        if(chosen<0) {
            throw new GLException("Graphics configuration chooser failed");
        }
        if(DEBUG) {
            System.err.println("Choosen "+caps[chosen]);
        }
        res = eglChooseConfig(eglDisplay, caps[chosen], absScreen);
        if(null!=res) {
            return res;
        }
        if(DEBUG) {
            System.err.println("eglChooseConfig failed with eglGetConfig/choosen capabilities");
        }

        // Last try .. add a fixed embedded profile [ATI, Nokia, ..]
        GLCapabilities fixedCaps = new GLCapabilities(glp);
        fixedCaps.setRedBits(5);
        fixedCaps.setGreenBits(6);
        fixedCaps.setBlueBits(5);
        fixedCaps.setDepthBits(16);
        fixedCaps.setSampleBuffers(true);
        fixedCaps.setNumSamples(4);
        if(DEBUG) {
            System.err.println("trying fixed caps: "+fixedCaps);
        }

        res = eglChooseConfig(eglDisplay, fixedCaps, absScreen);
        if(null==res) {
            throw new GLException("Graphics configuration failed [direct caps, eglGetConfig/chooser and fixed-caps]");
        }
        return res;
    }

    protected static EGLGraphicsConfiguration eglChooseConfig(long eglDisplay, GLCapabilities capabilities,
                                                              AbstractGraphicsScreen absScreen) {
        GLProfile glp = capabilities.getGLProfile();
        int[] attrs = EGLGraphicsConfiguration.GLCapabilities2AttribList(capabilities);
        _EGLConfig[] configs = new _EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL.eglChooseConfig(eglDisplay,
                                 attrs, 0,
                                 configs, 1,
                                 numConfigs, 0)) {
            throw new GLException("Graphics configuration selection (eglChooseConfig) failed");
        }
        if (numConfigs[0] > 0) {
            int[] val = new int[1];
            // get the configID 
            if(!EGL.eglGetConfigAttrib(eglDisplay, configs[0], EGL.EGL_CONFIG_ID, val, 0)) {
                if(DEBUG) {
                    // FIXME: this happens on a ATI PC Emulation ..
                    System.err.println("EGL couldn't retrieve ConfigID for already chosen eglConfig "+capabilities+", use 0");
                }
                val[0]=0;
            }

            return new EGLGraphicsConfiguration(absScreen, 
                            EGLGraphicsConfiguration.EGLConfig2Capabilities(glp, eglDisplay, configs[0]), configs[0], val[0]);
        }
        return null;
    }
}
