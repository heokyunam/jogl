/*
 * Copyright (c) 2008 Sun Microsystems, Inc. All Rights Reserved.
 * Copyright (c) 2010 JogAmp Community. All rights reserved.
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

package jogamp.opengl.windows.wgl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.media.nativewindow.AbstractGraphicsScreen;
import javax.media.nativewindow.NativeSurface;
import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.opengl.GL;
import javax.media.opengl.GLCapabilitiesImmutable;
import javax.media.opengl.GLCapabilitiesChooser;
import javax.media.opengl.GLDrawableFactory;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;

import com.jogamp.common.nio.Buffers;
import com.jogamp.nativewindow.MutableGraphicsConfiguration;

import jogamp.nativewindow.windows.DWM_BLURBEHIND;
import jogamp.nativewindow.windows.GDI;
import jogamp.nativewindow.windows.GDIUtil;
import jogamp.nativewindow.windows.MARGINS;
import jogamp.nativewindow.windows.PIXELFORMATDESCRIPTOR;
import jogamp.opengl.GLGraphicsConfigurationUtil;

public class WindowsWGLGraphicsConfiguration extends MutableGraphicsConfiguration implements Cloneable {
    protected static final int MAX_PFORMATS = 256;
    protected static final int MAX_ATTRIBS  = 256;

    private GLCapabilitiesChooser chooser;
    private boolean isDetermined = false;
    private boolean isExternal = false;

    WindowsWGLGraphicsConfiguration(AbstractGraphicsScreen screen,
                                    GLCapabilitiesImmutable capsChosen, GLCapabilitiesImmutable capsRequested,
                                    GLCapabilitiesChooser chooser) {
        super(screen, capsChosen, capsRequested);
        this.chooser=chooser;
        this.isDetermined = false;
    }

    WindowsWGLGraphicsConfiguration(AbstractGraphicsScreen screen,
                                    WGLGLCapabilities capsChosen, GLCapabilitiesImmutable capsRequested) {
        super(screen, capsChosen, capsRequested);
        setCapsPFD(capsChosen);
        this.chooser=null;
    }


    static WindowsWGLGraphicsConfiguration createFromExternal(GLDrawableFactory _factory, long hdc, int pfdID,
                                                             GLProfile glp, AbstractGraphicsScreen screen, boolean onscreen)
    {
        if(_factory==null) {
            throw new GLException("Null factory");
        }
        if(hdc==0) {
            throw new GLException("Null HDC");
        }
        if(pfdID<=0) {
            throw new GLException("Invalid pixelformat id "+pfdID);
        }
        if(null==glp) {
          glp = GLProfile.getDefault(screen.getDevice());
        }
        WindowsWGLDrawableFactory factory = (WindowsWGLDrawableFactory) _factory;
        AbstractGraphicsDevice device = screen.getDevice();
        WindowsWGLDrawableFactory.SharedResource sharedResource = factory.getOrCreateSharedResourceImpl(device);
        boolean hasARB = null != sharedResource && sharedResource.hasARBPixelFormat();

        WGLGLCapabilities caps = null;

        if(hasARB) {
            caps = wglARBPFID2GLCapabilities(sharedResource, device, glp, hdc, pfdID, GLGraphicsConfigurationUtil.ALL_BITS);
        } else {
            caps = PFD2GLCapabilities(device, glp, hdc, pfdID, GLGraphicsConfigurationUtil.ALL_BITS);
        }
        if(null==caps) {
            throw new GLException("Couldn't choose Capabilities by: HDC 0x"+Long.toHexString(hdc)+
                                  ", pfdID "+pfdID+", onscreen "+onscreen+", hasARB "+hasARB);
        }

        WindowsWGLGraphicsConfiguration cfg = new WindowsWGLGraphicsConfiguration(screen, caps, caps);
        cfg.markExternal();
        return cfg;
    }

    @Override
    public Object clone() {
        return super.clone();
    }

    /**
     * Updates the graphics configuration in case it has been determined yet.<br>
     * Uses the NativeSurface's HDC.<br>
     * Ensures that a PIXELFORMAT is set.
     *
     * @param factory
     * @param ns
     * @param pfIDs optional pool of preselected PixelFormat IDs, maybe null for unrestricted selection
     *
     * @see #isDetermined()
     * @see #isExternal()
     */
    public final void updateGraphicsConfiguration(GLDrawableFactory factory, NativeSurface ns, int[] pfIDs) {
        WindowsWGLGraphicsConfigurationFactory.updateGraphicsConfiguration(chooser, factory, ns, pfIDs);
    }

    /**
     * Preselect the graphics configuration in case it has been determined yet.<br>
     * Uses a shared device's HDC and the given pfdIDs to preselect the pfd.
     * No PIXELFORMAT is set.
     *
     * @param factory
     * @param pfIDs optional pool of preselected PixelFormat IDs, maybe null for unrestricted selection
     *
     * @see #isDetermined()
     */
    public final void preselectGraphicsConfiguration(GLDrawableFactory factory, int[] pfdIDs) {
        AbstractGraphicsDevice device = getScreen().getDevice();
        WindowsWGLGraphicsConfigurationFactory.preselectGraphicsConfiguration(chooser, factory, device, this, pfdIDs);
    }

    /**
     * Sets the hdc's PixelFormat, this configuration's capabilities and marks it as determined.
     */
    final void setPixelFormat(long hdc, WGLGLCapabilities caps) {
        if (0 == hdc) {
            throw new GLException("Error: HDC is null");
        }

        if (!WGLUtil.SetPixelFormat(hdc, caps.getPFDID(), caps.getPFD())) {
            throw new GLException("Unable to set pixel format " + caps.getPFDID() + " of " + caps +
                                  " for device context " + toHexString(hdc) +
                                  ": error code " + GDI.GetLastError());
        }
        if( !caps.isBackgroundOpaque() ) {
            final long hwnd = GDI.WindowFromDC(hdc);
            DWM_BLURBEHIND bb = DWM_BLURBEHIND.create();
            bb.setDwFlags(GDI.DWM_BB_ENABLE| GDI.DWM_BB_TRANSITIONONMAXIMIZED);
            bb.setFEnable( 1 );
            boolean ok = GDI.DwmEnableBlurBehindWindow(hwnd, bb);
            if( ok ) {
                MARGINS m = MARGINS.create();
                m.setCxLeftWidth(-1);
                m.setCxRightWidth(-1);
                m.setCyBottomHeight(-1);
                m.setCyTopHeight(-1);
                ok = GDI.DwmExtendFrameIntoClientArea(hwnd, m);
            }
            if(DEBUG) {
                final boolean isUndecorated = GDIUtil.IsUndecorated(hwnd);
                final boolean isChild = GDIUtil.IsChild(hwnd);
                System.err.println("translucency enabled on wnd: 0x"+Long.toHexString(hwnd)+" - isUndecorated "+isUndecorated+", isChild "+isChild+", ok: "+ok);
            }
        }
        if (DEBUG) {
            System.err.println("setPixelFormat: hdc "+toHexString(hdc) +", "+caps);
        }
        setCapsPFD(caps);
    }

    /**
     * Only sets this configuration's capabilities and marks it as determined,
     * the actual pixelformat is not set.
     */
    final void setCapsPFD(WGLGLCapabilities caps) {
        setChosenCapabilities(caps);
        this.isDetermined = true;
        if (DEBUG) {
            System.err.println("*** setCapsPFD: "+caps);
        }
    }

    /**
     * External configuration's HDC pixelformat shall not be modified
     */
    public final boolean isExternal() { return isExternal; }

    final void markExternal() {
        this.isExternal=true;
    }

    /**
     * Determined configuration states set target capabilties via {@link #setCapsPFD(WGLGLCapabilities)},
     * but does not imply a set pixelformat.
     *
     * @see #setPixelFormat(long, WGLGLCapabilities)
     * @see #setCapsPFD(WGLGLCapabilities)
     */
    public final boolean isDetermined() { return isDetermined; }

    public final PIXELFORMATDESCRIPTOR getPixelFormat()   { return isDetermined ? ((WGLGLCapabilities)capabilitiesChosen).getPFD() : null; }
    public final int getPixelFormatID() { return isDetermined ? ((WGLGLCapabilities)capabilitiesChosen).getPFDID() : 0; }
    public final boolean isChoosenByARB() { return isDetermined ? ((WGLGLCapabilities)capabilitiesChosen).isSetByARB() : false; }

    static int fillAttribsForGeneralWGLARBQuery(WindowsWGLDrawableFactory.SharedResource sharedResource, IntBuffer iattributes) {
        int niattribs = 0;
        iattributes.put(niattribs++, WGLExt.WGL_DRAW_TO_WINDOW_ARB);
        if(sharedResource.hasARBPBuffer()) {
            iattributes.put(niattribs++, WGLExt.WGL_DRAW_TO_PBUFFER_ARB);
        }
        iattributes.put(niattribs++, WGLExt.WGL_DRAW_TO_BITMAP_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ACCELERATION_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_SUPPORT_OPENGL_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_DEPTH_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_STENCIL_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_DOUBLE_BUFFER_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_STEREO_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_PIXEL_TYPE_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_RED_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_GREEN_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_BLUE_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ALPHA_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ACCUM_RED_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ACCUM_GREEN_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ACCUM_BLUE_BITS_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_ACCUM_ALPHA_BITS_ARB);
        if(sharedResource.hasARBMultisample()) {
            iattributes.put(niattribs++, WGLExt.WGL_SAMPLE_BUFFERS_ARB);
            iattributes.put(niattribs++, WGLExt.WGL_SAMPLES_ARB);
        }
        return niattribs;
    }

    static boolean wglARBPFIDValid(WindowsWGLContext sharedCtx, long hdc, int pfdID) {
        final IntBuffer out = Buffers.newDirectIntBuffer(1);
        final IntBuffer in = Buffers.newDirectIntBuffer(1);
        in.put(0, WGLExt.WGL_COLOR_BITS_ARB);
        if (!sharedCtx.getWGLExt().wglGetPixelFormatAttribivARB(hdc, pfdID, 0, 1, in, out)) {
            // Some GPU's falsely fails with a zero error code (success)
            return GDI.GetLastError() == GDI.ERROR_SUCCESS ;
        }
        return true;
    }

    static int wglARBPFDIDCount(WindowsWGLContext sharedCtx, long hdc) {
        final IntBuffer iresults = Buffers.newDirectIntBuffer(1);
        final IntBuffer iattributes = Buffers.newDirectIntBuffer(1);
        iattributes.put(0, WGLExt.WGL_NUMBER_PIXEL_FORMATS_ARB);

        WGLExt wglExt = sharedCtx.getWGLExt();
        // pfdID shall be ignored here (spec), however, pass a valid pdf index '1' below (possible driver bug)
        if (!wglExt.wglGetPixelFormatAttribivARB(hdc, 1 /* pfdID */, 0, 1, iattributes, iresults)) {
            if(DEBUG) {
                System.err.println("GetPixelFormatAttribivARB: Failed - HDC 0x" + Long.toHexString(hdc) +
                                  ", value "+iresults.get(0)+
                                  ", LastError: " + GDI.GetLastError());
                Thread.dumpStack();
            }
            return 0;
        }
        final int pfdIDCount = iresults.get(0);
        if(0 == pfdIDCount) {
            if(DEBUG) {
                System.err.println("GetPixelFormatAttribivARB: No formats - HDC 0x" + Long.toHexString(hdc) +
                                  ", LastError: " + GDI.GetLastError());
                Thread.dumpStack();
            }
        }
        return pfdIDCount;
    }

    static int[] wglAllARBPFDIDs(int pfdIDCount) {
        int[] pfdIDs = new int[pfdIDCount];
        for (int i = 0; i < pfdIDCount; i++) {
            pfdIDs[i] = 1 + i;
        }
        return pfdIDs;
    }

    static WGLGLCapabilities wglARBPFID2GLCapabilities(WindowsWGLDrawableFactory.SharedResource sharedResource,
                                                       AbstractGraphicsDevice device, GLProfile glp,
                                                       long hdc, int pfdID, int winattrbits) {
        if (!sharedResource.hasARBPixelFormat()) {
            return null;
        }

        final IntBuffer iattributes = Buffers.newDirectIntBuffer(2*MAX_ATTRIBS);
        final IntBuffer iresults = Buffers.newDirectIntBuffer(2*MAX_ATTRIBS);
        final int niattribs = fillAttribsForGeneralWGLARBQuery(sharedResource, iattributes);

        if (!((WindowsWGLContext)sharedResource.getContext()).getWGLExt().wglGetPixelFormatAttribivARB(hdc, pfdID, 0, niattribs, iattributes, iresults)) {
            throw new GLException("wglARBPFID2GLCapabilities: Error getting pixel format attributes for pixel format " + pfdID +
                                  " of device context " + toHexString(hdc) + ", werr " + GDI.GetLastError());
        }
        return AttribList2GLCapabilities(device, glp, hdc, pfdID, iattributes, niattribs, iresults, winattrbits);
    }

    static int[] wglChoosePixelFormatARB(WindowsWGLDrawableFactory.SharedResource sharedResource, AbstractGraphicsDevice device,
                                         GLCapabilitiesImmutable capabilities,
                                         long hdc, IntBuffer iattributes, int accelerationMode, FloatBuffer fattributes)
    {

        if ( !WindowsWGLGraphicsConfiguration.GLCapabilities2AttribList(capabilities,
                iattributes, sharedResource, accelerationMode, null))
        {
            if (DEBUG) {
                System.err.println("wglChoosePixelFormatARB: GLCapabilities2AttribList failed: " + GDI.GetLastError());
                Thread.dumpStack();
            }
            return null;
        }

        final WGLExt wglExt = ((WindowsWGLContext)sharedResource.getContext()).getWGLExt();
        final IntBuffer pformatsTmp = Buffers.newDirectIntBuffer(WindowsWGLGraphicsConfiguration.MAX_PFORMATS);
        final IntBuffer numFormatsTmp = Buffers.newDirectIntBuffer(1);

        if ( !wglExt.wglChoosePixelFormatARB(hdc, iattributes, fattributes,
                                             WindowsWGLGraphicsConfiguration.MAX_PFORMATS,
                                             pformatsTmp, numFormatsTmp) ) {
            if (DEBUG) {
                System.err.println("wglChoosePixelFormatARB: wglChoosePixelFormatARB failed: " + GDI.GetLastError());
                Thread.dumpStack();
            }
            return null;
        }
        final int numFormats = Math.min(numFormatsTmp.get(0), WindowsWGLGraphicsConfiguration.MAX_PFORMATS);
        final int[] pformats;
        if( 0 < numFormats ) {
            pformats = new int[numFormats];
            pformatsTmp.get(pformats, 0, numFormats);
        } else {
            pformats = null;
        }
        if (DEBUG) {
            System.err.println("wglChoosePixelFormatARB: NumFormats (wglChoosePixelFormatARB) accelMode 0x"
                    + Integer.toHexString(accelerationMode) + ": " + numFormats);
            for (int i = 0; i < numFormats; i++) {
                WGLGLCapabilities dbgCaps0 = WindowsWGLGraphicsConfiguration.wglARBPFID2GLCapabilities(
                                                sharedResource, device, capabilities.getGLProfile(), hdc, pformats[i], GLGraphicsConfigurationUtil.ALL_BITS);
                System.err.println("pixel format " + pformats[i] + " (index " + i + "): " + dbgCaps0);
            }
        }
        return pformats;
    }

    static List <GLCapabilitiesImmutable> wglARBPFIDs2GLCapabilities(WindowsWGLDrawableFactory.SharedResource sharedResource,
                                                                     AbstractGraphicsDevice device, GLProfile glp, long hdc, int[] pfdIDs, int winattrbits) {
        if (!sharedResource.hasARBPixelFormat()) {
            return null;
        }
        final int numFormats = pfdIDs.length;

        final IntBuffer iattributes = Buffers.newDirectIntBuffer(2*MAX_ATTRIBS);
        final IntBuffer iresults = Buffers.newDirectIntBuffer(2*MAX_ATTRIBS);
        final int niattribs = fillAttribsForGeneralWGLARBQuery(sharedResource, iattributes);

        ArrayList<GLCapabilitiesImmutable> bucket = new ArrayList<GLCapabilitiesImmutable>();

        for(int i = 0; i<numFormats; i++) {
            if ( pfdIDs[i] >= 1 &&
                 ((WindowsWGLContext)sharedResource.getContext()).getWGLExt().wglGetPixelFormatAttribivARB(hdc, pfdIDs[i], 0, niattribs, iattributes, iresults) ) {
                final GLCapabilitiesImmutable caps = AttribList2GLCapabilities(device, glp, hdc, pfdIDs[i], iattributes, niattribs, iresults, winattrbits);
                if(null != caps) {
                    bucket.add(caps);
                    if(DEBUG) {
                        final int j = bucket.size() - 1;
                        System.err.println("wglARBPFIDs2GLCapabilities: bucket["+i+" -> "+j+"]: "+caps);
                    }
                } else if(DEBUG) {
                    GLCapabilitiesImmutable skipped = AttribList2GLCapabilities(device, glp, hdc, pfdIDs[i], iattributes, niattribs, iresults, GLGraphicsConfigurationUtil.ALL_BITS);
                    System.err.println("wglARBPFIDs2GLCapabilities: bucket["+i+" -> skip]: pfdID "+pfdIDs[i]+", "+skipped+", winattr "+GLGraphicsConfigurationUtil.winAttributeBits2String(null, winattrbits).toString());
                }
            } else if (DEBUG) {
                if( 1 > pfdIDs[i] ) {
                    System.err.println("wglARBPFIDs2GLCapabilities: Invalid pfdID " + i + "/" + numFormats + ": " + pfdIDs[i]);
                } else {
                    System.err.println("wglARBPFIDs2GLCapabilities: Cannot get pixel format attributes for pixel format " +
                                       i + "/" + numFormats + ": " + pfdIDs[i] + ", hdc " + toHexString(hdc));
                }
            }
        }
        return bucket;
    }

    static boolean GLCapabilities2AttribList(GLCapabilitiesImmutable caps,
                                             IntBuffer iattributes,
                                             WindowsWGLDrawableFactory.SharedResource sharedResource,
                                             int accelerationValue,
                                             int[] floatMode) throws GLException {
        if (!sharedResource.hasARBPixelFormat()) {
          return false;
        }

        int niattribs = 0;

        iattributes.put(niattribs++, WGLExt.WGL_SUPPORT_OPENGL_ARB);
        iattributes.put(niattribs++, GL.GL_TRUE);
        if(accelerationValue>0) {
            iattributes.put(niattribs++, WGLExt.WGL_ACCELERATION_ARB);
            iattributes.put(niattribs++, accelerationValue);
        }

        final boolean usePBuffer = caps.isPBuffer() && sharedResource.hasARBPBuffer() ;

        final int surfaceType;
        if( caps.isOnscreen() ) {
            surfaceType = WGLExt.WGL_DRAW_TO_WINDOW_ARB;
        } else if( caps.isFBO() ) {
            surfaceType = WGLExt.WGL_DRAW_TO_WINDOW_ARB;  // native replacement!
        } else if( usePBuffer ) {
            surfaceType = WGLExt.WGL_DRAW_TO_PBUFFER_ARB;
        } else if( caps.isBitmap() ) {
            surfaceType = WGLExt.WGL_DRAW_TO_BITMAP_ARB;
        } else {
            throw new GLException("no surface type set in caps: "+caps);
        }
        iattributes.put(niattribs++, surfaceType);
        iattributes.put(niattribs++, GL.GL_TRUE);

        iattributes.put(niattribs++, WGLExt.WGL_DOUBLE_BUFFER_ARB);
        if (caps.getDoubleBuffered()) {
          iattributes.put(niattribs++, GL.GL_TRUE);
        } else {
          iattributes.put(niattribs++, GL.GL_FALSE);
        }

        iattributes.put(niattribs++, WGLExt.WGL_STEREO_ARB);
        if (caps.getStereo()) {
          iattributes.put(niattribs++, GL.GL_TRUE);
        } else {
          iattributes.put(niattribs++, GL.GL_FALSE);
        }

        iattributes.put(niattribs++, WGLExt.WGL_RED_BITS_ARB);
        iattributes.put(niattribs++, caps.getRedBits());
        iattributes.put(niattribs++, WGLExt.WGL_GREEN_BITS_ARB);
        iattributes.put(niattribs++, caps.getGreenBits());
        iattributes.put(niattribs++, WGLExt.WGL_BLUE_BITS_ARB);
        iattributes.put(niattribs++, caps.getBlueBits());
        if(caps.getAlphaBits()>0) {
            iattributes.put(niattribs++, WGLExt.WGL_ALPHA_BITS_ARB);
            iattributes.put(niattribs++, caps.getAlphaBits());
        }
        if(caps.getStencilBits()>0) {
            iattributes.put(niattribs++, WGLExt.WGL_STENCIL_BITS_ARB);
            iattributes.put(niattribs++, caps.getStencilBits());
        }
        iattributes.put(niattribs++, WGLExt.WGL_DEPTH_BITS_ARB);
        iattributes.put(niattribs++, caps.getDepthBits());
        if (caps.getAccumRedBits()   > 0 ||
            caps.getAccumGreenBits() > 0 ||
            caps.getAccumBlueBits()  > 0 ||
            caps.getAccumAlphaBits() > 0) {
          iattributes.put(niattribs++, WGLExt.WGL_ACCUM_BITS_ARB);
          iattributes.put(niattribs++, ( caps.getAccumRedBits() +
                                         caps.getAccumGreenBits() +
                                         caps.getAccumBlueBits() +
                                         caps.getAccumAlphaBits() ) );
          iattributes.put(niattribs++, WGLExt.WGL_ACCUM_RED_BITS_ARB);
          iattributes.put(niattribs++, caps.getAccumRedBits());
          iattributes.put(niattribs++, WGLExt.WGL_ACCUM_GREEN_BITS_ARB);
          iattributes.put(niattribs++, caps.getAccumGreenBits());
          iattributes.put(niattribs++, WGLExt.WGL_ACCUM_BLUE_BITS_ARB);
          iattributes.put(niattribs++, caps.getAccumBlueBits());
          iattributes.put(niattribs++, WGLExt.WGL_ACCUM_ALPHA_BITS_ARB);
          iattributes.put(niattribs++, caps.getAccumAlphaBits());
        }

        if (caps.getSampleBuffers() && sharedResource.hasARBMultisample()) {
            iattributes.put(niattribs++, WGLExt.WGL_SAMPLE_BUFFERS_ARB);
            iattributes.put(niattribs++, GL.GL_TRUE);
            iattributes.put(niattribs++, WGLExt.WGL_SAMPLES_ARB);
            iattributes.put(niattribs++, caps.getNumSamples());
        }

        iattributes.put(niattribs++, WGLExt.WGL_PIXEL_TYPE_ARB);
        iattributes.put(niattribs++, WGLExt.WGL_TYPE_RGBA_ARB);
        iattributes.put(niattribs++, 0);

        return true;
    }

    static int AttribList2DrawableTypeBits(final IntBuffer iattribs,
                                           final int niattribs, final IntBuffer iresults) {
        int val = 0;

        for (int i = 0; i < niattribs; i++) {
          final int attr = iattribs.get(i);
          switch (attr) {
            case WGLExt.WGL_DRAW_TO_WINDOW_ARB:
                if(iresults.get(i) == GL.GL_TRUE) {
                    val |= GLGraphicsConfigurationUtil.WINDOW_BIT |
                           GLGraphicsConfigurationUtil.FBO_BIT;
                }
                break;
            case WGLExt.WGL_DRAW_TO_BITMAP_ARB:
                if(iresults.get(i) == GL.GL_TRUE) {
                    val |= GLGraphicsConfigurationUtil.BITMAP_BIT;
                }
                break;
            case WGLExt.WGL_DRAW_TO_PBUFFER_ARB:
                if(iresults.get(i) == GL.GL_TRUE) {
                    val |= GLGraphicsConfigurationUtil.PBUFFER_BIT;
                }
                break;
            }
        }
        return val;
    }

    static WGLGLCapabilities AttribList2GLCapabilities(final AbstractGraphicsDevice device,
                                                       final GLProfile glp, final long hdc, final int pfdID,
                                                       final IntBuffer iattribs, final int niattribs, IntBuffer iresults, final int winattrmask) {
        final int allDrawableTypeBits = AttribList2DrawableTypeBits(iattribs, niattribs, iresults);
        int drawableTypeBits = winattrmask & allDrawableTypeBits;

        if( 0 == drawableTypeBits ) {
            return null;
        }
        PIXELFORMATDESCRIPTOR pfd = createPixelFormatDescriptor();

        if (WGLUtil.DescribePixelFormat(hdc, pfdID, PIXELFORMATDESCRIPTOR.size(), pfd) == 0) {
            // remove displayable bits, since pfdID is non displayable
            drawableTypeBits = drawableTypeBits & ~(GLGraphicsConfigurationUtil.WINDOW_BIT | GLGraphicsConfigurationUtil.BITMAP_BIT | GLGraphicsConfigurationUtil.FBO_BIT );
            if( 0 == drawableTypeBits ) {
                return null;
            }
            // non displayable requested (pbuffer)
        }
        final WGLGLCapabilities res = new WGLGLCapabilities(pfd, pfdID, glp);
        res.setValuesByARB(iattribs, niattribs, iresults);
        return (WGLGLCapabilities) GLGraphicsConfigurationUtil.fixWinAttribBitsAndHwAccel(device, drawableTypeBits, res);
    }

    //
    // GDI PIXELFORMAT
    //

    static int[] wglAllGDIPFIDs(long hdc) {
        int numFormats = WGLUtil.DescribePixelFormat(hdc, 1, 0, null);
        if (numFormats == 0) {
            throw new GLException("DescribePixelFormat: No formats - HDC 0x" + Long.toHexString(hdc) +
                                  ", LastError: " + GDI.GetLastError());
        }
        int[] pfdIDs = new int[numFormats];
        for (int i = 0; i < numFormats; i++) {
            pfdIDs[i] = 1 + i;
        }
        return pfdIDs;
    }

    static int PFD2DrawableTypeBits(PIXELFORMATDESCRIPTOR pfd) {
        int val = 0;

        int dwFlags = pfd.getDwFlags();

        if( 0 != (GDI.PFD_DRAW_TO_WINDOW & dwFlags ) ) {
            val |= GLGraphicsConfigurationUtil.WINDOW_BIT |
                   GLGraphicsConfigurationUtil.FBO_BIT;
        }
        if( 0 != (GDI.PFD_DRAW_TO_BITMAP & dwFlags ) ) {
            val |= GLGraphicsConfigurationUtil.BITMAP_BIT;
        }
        return val;
    }

    static WGLGLCapabilities PFD2GLCapabilities(AbstractGraphicsDevice device, final GLProfile glp, final long hdc, final int pfdID, final int winattrmask) {
        PIXELFORMATDESCRIPTOR pfd = createPixelFormatDescriptor(hdc, pfdID);
        if(null == pfd) {
            return null;
        }
        if ( (pfd.getDwFlags() & GDI.PFD_SUPPORT_OPENGL) == 0) {
            return null;
        }
        final int allDrawableTypeBits = PFD2DrawableTypeBits(pfd);
        final int drawableTypeBits = winattrmask & allDrawableTypeBits;

        if( 0 == drawableTypeBits ) {
            if(DEBUG) {
                System.err.println("Drop [drawableType mismatch]: " + WGLGLCapabilities.PFD2String(pfd, pfdID));
            }
            return null;
        }
        if( GLGraphicsConfigurationUtil.BITMAP_BIT == drawableTypeBits ) {
            // BITMAP exclusive PFD SafeGuard: Only accept BITMAP compatible color formats!
            final int pfdColorBits = pfd.getCColorBits();
            if ( pfdColorBits != 24 || 0 < pfd.getCAlphaBits() ) { // Allowed: RGB888 && !alpha
                if(DEBUG) {
                    System.err.println("Drop [color bits excl BITMAP]: " + WGLGLCapabilities.PFD2String(pfd, pfdID));
                }
                return null;
            }
        }

        final WGLGLCapabilities res = new WGLGLCapabilities(pfd, pfdID, glp);
        res.setValuesByGDI();
        return (WGLGLCapabilities) GLGraphicsConfigurationUtil.fixWinAttribBitsAndHwAccel(device, drawableTypeBits, res);
   }

    static WGLGLCapabilities PFD2GLCapabilitiesNoCheck(AbstractGraphicsDevice device, final GLProfile glp, final long hdc, final int pfdID) {
        PIXELFORMATDESCRIPTOR pfd = createPixelFormatDescriptor(hdc, pfdID);
        return PFD2GLCapabilitiesNoCheck(device, glp, pfd, pfdID);
   }

   static WGLGLCapabilities PFD2GLCapabilitiesNoCheck(AbstractGraphicsDevice device, GLProfile glp, PIXELFORMATDESCRIPTOR pfd, int pfdID) {
        if(null == pfd) {
            return null;
        }
        final WGLGLCapabilities res = new WGLGLCapabilities(pfd, pfdID, glp);
        res.setValuesByGDI();

        return (WGLGLCapabilities) GLGraphicsConfigurationUtil.fixWinAttribBitsAndHwAccel(device, PFD2DrawableTypeBits(pfd), res);
   }

   static PIXELFORMATDESCRIPTOR GLCapabilities2PFD(GLCapabilitiesImmutable caps, PIXELFORMATDESCRIPTOR pfd) {
       int colorDepth = (caps.getRedBits() +
               caps.getGreenBits() +
               caps.getBlueBits());
       if (colorDepth < 15) {
           throw new GLException("Bit depths < 15 (i.e., non-true-color) not supported");
       }
       int pfdFlags = ( GDI.PFD_SUPPORT_OPENGL | GDI.PFD_GENERIC_ACCELERATED );

       if( caps.isOnscreen() ) {
           pfdFlags |= GDI.PFD_DRAW_TO_WINDOW;
       } else if( caps.isFBO() ) {
           pfdFlags |= GDI.PFD_DRAW_TO_WINDOW; // native replacement!
       } else if( caps.isPBuffer() ) {
           pfdFlags |= GDI.PFD_DRAW_TO_BITMAP; // pbuffer n/a, use bitmap
       } else if( caps.isBitmap() ) {
           pfdFlags |= GDI.PFD_DRAW_TO_BITMAP;
       } else {
           throw new GLException("no surface type set in caps: "+caps);
       }

       if ( caps.getDoubleBuffered() ) {
           if( caps.isBitmap() || caps.isPBuffer() ) {
               pfdFlags |= GDI.PFD_DOUBLEBUFFER_DONTCARE; // bitmaps probably don't have dbl buffering
           } else {
               pfdFlags |= GDI.PFD_DOUBLEBUFFER;
           }
       }

       if (caps.getStereo()) {
           pfdFlags |= GDI.PFD_STEREO;
       }
       pfd.setDwFlags(pfdFlags);
       pfd.setIPixelType((byte) GDI.PFD_TYPE_RGBA);
       pfd.setCColorBits((byte) colorDepth);
       pfd.setCRedBits  ((byte) caps.getRedBits());
       pfd.setCGreenBits((byte) caps.getGreenBits());
       pfd.setCBlueBits ((byte) caps.getBlueBits());
       pfd.setCAlphaBits((byte) caps.getAlphaBits());
       int accumDepth = (caps.getAccumRedBits() +
               caps.getAccumGreenBits() +
               caps.getAccumBlueBits());
       pfd.setCAccumBits     ((byte) accumDepth);
       pfd.setCAccumRedBits  ((byte) caps.getAccumRedBits());
       pfd.setCAccumGreenBits((byte) caps.getAccumGreenBits());
       pfd.setCAccumBlueBits ((byte) caps.getAccumBlueBits());
       pfd.setCAccumAlphaBits((byte) caps.getAccumAlphaBits());
       pfd.setCDepthBits((byte) caps.getDepthBits());
       pfd.setCStencilBits((byte) caps.getStencilBits());
       pfd.setILayerType((byte) GDI.PFD_MAIN_PLANE);

       // n/a with non ARB/GDI method:
       //       multisample
       //       opaque
       //       pbuffer
       return pfd;
   }

   static PIXELFORMATDESCRIPTOR createPixelFormatDescriptor(long hdc, int pfdID) {
       PIXELFORMATDESCRIPTOR pfd = PIXELFORMATDESCRIPTOR.create();
       pfd.setNSize((short) PIXELFORMATDESCRIPTOR.size());
       pfd.setNVersion((short) 1);
       if(0 != hdc && 1 <= pfdID) {
           if (WGLUtil.DescribePixelFormat(hdc, pfdID, PIXELFORMATDESCRIPTOR.size(), pfd) == 0) {
               // Accelerated pixel formats that are non displayable
               if(DEBUG) {
                   System.err.println("Info: Non displayable pixel format " + pfdID + " of device context: error code " + GDI.GetLastError());
               }
               return null;
           }
       }
       return pfd;
   }

   static PIXELFORMATDESCRIPTOR createPixelFormatDescriptor() {
       return createPixelFormatDescriptor(0, 0);
   }

   @Override
   public String toString() {
       return "WindowsWGLGraphicsConfiguration["+getScreen()+", pfdID " + getPixelFormatID() + ", ARB-Choosen " + isChoosenByARB() +
               ",\n\trequested " + getRequestedCapabilities() +
               ",\n\tchosen    " + getChosenCapabilities() +
               "]";
   }
}

