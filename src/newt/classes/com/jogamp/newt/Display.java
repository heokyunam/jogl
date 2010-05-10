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
 * 
 */

package com.jogamp.newt;

import com.jogamp.newt.event.*;
import javax.media.nativewindow.*;
import com.jogamp.newt.impl.Debug;
import com.jogamp.newt.util.EDTUtil;
import java.util.*;

public abstract class Display {
    public static final boolean DEBUG = Debug.debug("Display");

    private static Class getDisplayClass(String type) 
        throws ClassNotFoundException 
    {
        Class displayClass = NewtFactory.getCustomClass(type, "Display");
        if(null==displayClass) {
            if (NativeWindowFactory.TYPE_EGL.equals(type)) {
                displayClass = Class.forName("com.jogamp.newt.impl.opengl.kd.KDDisplay");
            } else if (NativeWindowFactory.TYPE_WINDOWS.equals(type)) {
                displayClass = Class.forName("com.jogamp.newt.impl.windows.WindowsDisplay");
            } else if (NativeWindowFactory.TYPE_MACOSX.equals(type)) {
                displayClass = Class.forName("com.jogamp.newt.impl.macosx.MacDisplay");
            } else if (NativeWindowFactory.TYPE_X11.equals(type)) {
                displayClass = Class.forName("com.jogamp.newt.impl.x11.X11Display");
            } else if (NativeWindowFactory.TYPE_AWT.equals(type)) {
                displayClass = Class.forName("com.jogamp.newt.impl.awt.AWTDisplay");
            } else {
                throw new RuntimeException("Unknown display type \"" + type + "\"");
            }
        }
        return displayClass;
    }

    // Unique Display for each thread
    private static ThreadLocal currentDisplayMap = new ThreadLocal();

    /** Returns the thread local display map */
    public static Map getCurrentDisplayMap() {
        Map displayMap = (Map) currentDisplayMap.get();
        if(null==displayMap) {
            displayMap = new HashMap();
            currentDisplayMap.set( displayMap );
        }
        return displayMap;
    }

    /** maps the given display to the thread local display map
      * and notifies all threads synchronized to this display map. */
    protected static Display setCurrentDisplay(Display display) {
        Map displayMap = getCurrentDisplayMap();
        Display oldDisplay = null;
        synchronized(displayMap) {
            oldDisplay = (Display) displayMap.put(display.getFQName(), display);
            displayMap.notifyAll();
        }
        return oldDisplay;
    }

    /** removes the mapping of the given name from the thread local display map
      * and notifies all threads synchronized to this display map. */
    protected static Display removeCurrentDisplay(String type, String name) {
        Map displayMap = getCurrentDisplayMap();
        Display oldDisplay = null;
        synchronized(displayMap) {
            oldDisplay = (Display) displayMap.remove(getFQName(type,name));
            displayMap.notifyAll();
        }
        return oldDisplay;
    }

    /** Returns the thread local display mapped to the given name */
    public static Display getCurrentDisplay(String type, String name) {
        Map displayMap = getCurrentDisplayMap();
        Display display = (Display) displayMap.get(getFQName(type,name));
        return display;
    }

    public static void dumpDisplayMap(String prefix) {
        Map displayMap = getCurrentDisplayMap();
        Set entrySet = displayMap.entrySet();
        Iterator i = entrySet.iterator();
        System.err.println(prefix+" DisplayMap["+entrySet.size()+"] "+Thread.currentThread());
        for(int j=0; i.hasNext(); j++) {
            Map.Entry entry = (Map.Entry) i.next();
            System.err.println("  ["+j+"] "+entry.getKey()+" -> "+entry.getValue());
        }
    }

    /** Returns the thread local display collection */
    public static Collection getCurrentDisplays() {
        return getCurrentDisplayMap().values();
    }

    /** Make sure to reuse a Display with the same name */
    protected static Display create(String type, String name) {
        try {
            if(DEBUG) {
                dumpDisplayMap("Display.create("+getFQName(type, name)+") BEGIN");
            }
            Display display = getCurrentDisplay(type, name);
            if(null==display) {
                Class displayClass = getDisplayClass(type);
                display = (Display) displayClass.newInstance();
                display.name=name;
                display.type=type;
                display.refCount=1;

                if(NewtFactory.useEDT()) {
                    final Display f_dpy = display;
                    Thread current = Thread.currentThread();
                    display.edtUtil = new EDTUtil(current.getThreadGroup(), 
                                                  "Display_"+display.getFQName()+"-"+current.getName(),
                                                  new Runnable() {
                                                    public void run() {
                                                        if(null!=f_dpy.getGraphicsDevice()) {
                                                            f_dpy.pumpMessagesImpl();
                                                        }
                                                    } } );
                    display.edt = display.edtUtil.start();
                    display.edtUtil.invokeAndWait(new Runnable() {
                        public void run() {
                            f_dpy.createNative();
                        }
                    } );
                } else {
                    display.createNative();
                }
                if(null==display.aDevice) {
                    throw new RuntimeException("Display.createNative() failed to instanciate an AbstractGraphicsDevice");
                }
                setCurrentDisplay(display);
                if(DEBUG) {
                    System.err.println("Display.create("+getFQName(type, name)+") NEW: "+display+" "+Thread.currentThread());
                }
            } else {
                synchronized(display) {
                    display.refCount++;
                    if(DEBUG) {
                        System.err.println("Display.create("+getFQName(type, name)+") REUSE: refCount "+display.refCount+", "+display+" "+Thread.currentThread());
                    }
                }
            }
            if(DEBUG) {
                dumpDisplayMap("Display.create("+getFQName(type, name)+") END");
            }
            return display;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static Display wrapHandle(String type, String name, AbstractGraphicsDevice aDevice) {
        try {
            Class displayClass = getDisplayClass(type);
            Display display = (Display) displayClass.newInstance();
            display.name=name;
            display.type=type;
            display.aDevice=aDevice;
            return display;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public EDTUtil getEDTUtil() { return edtUtil; }

    public synchronized void destroy() {
        if(DEBUG) {
            dumpDisplayMap("Display.destroy("+getFQName()+") BEGIN");
        }
        refCount--;
        if(0==refCount) {
            removeCurrentDisplay(type, name);
            if(DEBUG) {
                System.err.println("Display.destroy("+getFQName()+") REMOVE: "+this+" "+Thread.currentThread());
            }
            if(null!=edtUtil) {
                final Display f_dpy = this;
                final EDTUtil f_edt = edtUtil;
                edtUtil.invokeAndWait(new Runnable() {
                    public void run() {
                        f_dpy.closeNative();
                        f_edt.stop();
                    }
                } );
            } else {
                closeNative();
            }
            if(null!=edtUtil) { 
                edtUtil.waitUntilStopped();
                edtUtil=null;
            }
            aDevice = null;
        } else {
            if(DEBUG) {
                System.err.println("Display.destroy("+getFQName()+") KEEP: refCount "+refCount+", "+this+" "+Thread.currentThread());
            }
        }
        if(DEBUG) {
            dumpDisplayMap("Display.destroy("+getFQName()+") END");
        }
    }

    protected abstract void createNative();
    protected abstract void closeNative();

    public final String getType() {
        return type;
    }

    public final String getName() {
        return name;
    }

    public final String getFQName() {
        return getFQName(type, name);
    }

    static final String nilString = "nil" ;

    public static final String getFQName(String type, String name) {
        if(null==type) type=nilString;
        if(null==name) name=nilString;
        return type+":"+name;
    }

    public long getHandle() {
        if(null!=aDevice) {
            return aDevice.getHandle();
        }
        return 0;
    }

    public AbstractGraphicsDevice getGraphicsDevice() {
        return aDevice;
    }

    public synchronized void pumpMessages() {
        pumpMessagesImpl();
    }

    private void pumpMessagesImpl() {
        if(0==refCount) return;
        dispatchMessages();
    }

    public String toString() {
        return "NEWT-Display["+getFQName()+", refCount "+refCount+", "+aDevice+"]";
    }

    protected abstract void dispatchMessagesNative();

    private LinkedList/*<NEWTEvent>*/ events = new LinkedList();

    protected void dispatchMessages() {
        NEWTEvent e;
        do {
            synchronized(events) {
                if (!events.isEmpty()) {
                    e = (NEWTEvent) events.removeFirst();
                } else {
                    e = null;
                }
            }
            if (e != null) {
                Object source = e.getSource();
                if(source instanceof Window) {
                    ((Window)source).sendEvent(e);
                } else {
                    throw new RuntimeException("Event source not a NEWT Window: "+source.getClass().getName()+", "+source);
                }
            }
        } while (e != null);

        dispatchMessagesNative();
    }

    public void enqueueEvent(NEWTEvent e) {
        synchronized(events) {
            events.addLast(e);
        }
    }


    /** Default impl. nop - Currently only X11 needs a Display lock */
    protected void lockDisplay() { }

    /** Default impl. nop - Currently only X11 needs a Display lock */
    protected void unlockDisplay() { }

    protected EDTUtil edtUtil = null;
    protected Thread  edt = null;
    protected String name;
    protected String type;
    protected int refCount;
    protected AbstractGraphicsDevice aDevice;
}
