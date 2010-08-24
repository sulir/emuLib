/*
 * Context.java
 *
 * (c) Copyright 2010, P. Jakubčo <pjakubco@gmail.com>
 *
 * KISS, YAGNI
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program; if not, write to the Free Software Foundation, Inc.,
 *  51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package runtime;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;
import plugins.IContext;
import plugins.compiler.ICompilerContext;
import plugins.cpu.ICPUContext;
import plugins.device.IDeviceContext;
import plugins.memory.IMemoryContext;
import runtime.interfaces.IConnections;

/**
 * This class manages all contexts. Plug-ins should register their contexts
 * manually. This class also checks the correct hash of the context interfaces
 *
 * @author vbmacher
 */
public class Context {
    // the following tables store all registered contexts.
    // Contexts implementing the same context interfaces are stored
    // to the end of the arraylist under the same hashtable key
    private Hashtable<Class<?>,ArrayList<ICompilerContext>> compilerContexts;
    private Hashtable<Class<?>,ArrayList<ICPUContext>> cpuContexts;
    private Hashtable<Class<?>,ArrayList<IMemoryContext>> memContexts;
    private Hashtable<Class<?>,ArrayList<IDeviceContext>> deviceContexts;

    // This hashtable represents owners of registered contexts (keys).
    // It is used for checking the plug-in permissions to access them
    private Hashtable<Long,ArrayList<IContext>> contextOwners;

    // instance of this class
    private static Context instance = null;

    // emuStudio communication
    private static String emuStudioHash = null;
    private IConnections computer;

    /**
     * Private constructor.
     */
    private Context() {
        compilerContexts = new Hashtable<Class<?>,
                ArrayList<ICompilerContext>>();
        cpuContexts = new Hashtable<Class<?>, ArrayList<ICPUContext>>();
        memContexts = new Hashtable<Class<?>,
                ArrayList<IMemoryContext>>();
        deviceContexts = new Hashtable<Class<?>,
                ArrayList<IDeviceContext>>();

        contextOwners = new Hashtable<Long,ArrayList<IContext>>();
        computer = null;
    }

    /**
     * Return an instance of this class. By calling more than 1 time, the same
     * instance is returned.
     *
     * @return Context instance
     */
    public static Context getInstance() {
        if (instance == null)
            instance = new Context();
        return instance;
    }

    /**
     * Test the class for the implementation of the given interface.
     *
     * @param classI class that will be tested
     * @param interfaceName interface that the class should implement
     * @return true if the class implements given interface, false otherwise
     */
    private static boolean testInterface(Class<?> classI, Class<?> interfaceName) {
        Class<?>[] intf = classI.getInterfaces();

        for (int j = 0; j < intf.length; j++)
            if (intf[j].isInterface() && intf[j].equals(interfaceName))
                return true;
        return false;
    }

    /**
     * This method registers context interface implemented by plug-in.
     * The registration is needed of two reasons:
     *  - for connections realized by emuStudio
     *  - for check of correctness of the context hash
     *
     * If the plug-in asks for a context, it has to know the hash of it.
     *
     * Requirements for the context:
     *   It is allowed (and required) to implement one and the only one
     *   interface of a plug-in context type (ie. one of the ICPUContext,
     *   IMemoryContext, ICompilerContext, or IDeviceContext)
     *
     * @param pluginID
     *        ID of the plugin
     * @param context
     *        The context that the plug-in want to register. It HAS TO
     *        be a class, not an interface.
     * @param contextInterface
     *        The interface that the context has to implement. This is the
     *        extended context. It HAS TO be an interface, not a class.
     * @return true if the registration is successful, false if it fails.
     */
    public boolean register(long pluginID, IContext context,
            Class<?> contextInterface) {

        // check if the context is class
        if (context.getClass().isInterface())
            return false;
        // check if the contextInterface is interface
        if (!contextInterface.isInterface())
            return false;

        // if the context is already registered, return false
        ArrayList tt = cpuContexts.get(contextInterface);
        if ((tt != null) && tt.contains(context))
            return false;
        tt = memContexts.get(contextInterface);
        if ((tt != null) && tt.contains(context))
            return false;
        tt = deviceContexts.get(contextInterface);
        if ((tt != null) && tt.contains(context))
            return false;
        tt = compilerContexts.get(contextInterface);
        if ((tt != null) && tt.contains(context))
            return false;

        // check if the contextInterface is implemented by context
        Class c = context.getClass();
        Class<?> tmp;
        boolean positive = false;
        while ((!(positive = testInterface(c, contextInterface)))
                && ((tmp = c.getSuperclass()) != null)
                && (!tmp.isInterface()) && (!tmp.equals(Object.class))) {
            c = tmp;
        }

        if (!positive) {
            positive = testInterface(c, contextInterface);
        }

        if (!positive)
            return false;

        // check hash of the interface
        String hash = null;
        String contextIName = contextInterface.getSimpleName();

        if (contextIName.equals(ICPUContext.class.getSimpleName())
                && (context instanceof ICPUContext))
            hash = computeHash(ICPUContext.class);
        else if (contextIName.equals(ICompilerContext.class.getSimpleName())
                && (context instanceof ICompilerContext))
            hash = computeHash(ICompilerContext.class);
        else if (contextIName.equals(IDeviceContext.class.getSimpleName())
                && (context instanceof IDeviceContext))
            hash = computeHash(IDeviceContext.class);
        else if (contextIName.equals(IMemoryContext.class.getSimpleName())
                && (context instanceof IMemoryContext))
            hash = computeHash(IMemoryContext.class);
        else {
            // extract hash from interface name
            hash = contextIName.substring(1);
        }
        if (checkHash(contextInterface, hash) == false)
            return false;

        // finally register context
        ArrayList<IContext> ar = contextOwners.get(pluginID);
        if (ar == null) {
            ar = new ArrayList<IContext>();
            contextOwners.put(pluginID, ar);
        }
        ar.add(context);

        if (context instanceof ICPUContext) {
            ArrayList<ICPUContext> arc = cpuContexts.get(contextInterface);
            if (arc == null) {
                arc = new ArrayList<ICPUContext>();
                cpuContexts.put(contextInterface, arc);
            }
            arc.add((ICPUContext)context);
        } else if (context instanceof ICompilerContext) {
            ArrayList<ICompilerContext> arc = compilerContexts.get(contextInterface);
            if (arc == null) {
                arc = new ArrayList<ICompilerContext>();
                compilerContexts.put(contextInterface, arc);
            }
            arc.add((ICompilerContext)context);
        } else if (context instanceof IDeviceContext) {
            ArrayList<IDeviceContext> arc = deviceContexts.get(contextInterface);
            if (arc == null) {
                arc = new ArrayList<IDeviceContext>();
                deviceContexts.put(contextInterface, arc);
            }
            arc.add((IDeviceContext)context);
        } else if (context instanceof IMemoryContext) {
            ArrayList<IMemoryContext> arc = memContexts.get(contextInterface);
            if (arc == null) {
                arc = new ArrayList<IMemoryContext>();
                memContexts.put(contextInterface, arc);
            }
            arc.add((IMemoryContext)context);
        } else {
            // This if branch IS needed
            return false;
        }
        return true;
    }

    /**
     * Method removes context from a context hashtable by value, not key.
     * It searches through the ArrayList for the value. If the ArrayList
     * is empty, removes also the key from the hashtable.
     *
     * @param t hashtable
     * @param context value that shall be removed
     * @return true if the value has been removed (and was found in the hashtable)
     */
    private boolean removeContext(Hashtable<Class<?>,?> t,
            IContext context) {
        if (t == null)
            return false;
        Enumeration<Class<?>> e = t.keys();
        while (e.hasMoreElements()) {
            Class<?> intf = e.nextElement();
            ArrayList<?> ar = (ArrayList<?>)t.get(intf);
            if (ar == null)
                continue;
            if (ar.contains(context)) {
                boolean b = ar.remove(context);
                if (ar.isEmpty())
                    t.remove(intf);
                return b;
            }
        }
        return false;
    }

    /**
     * Method removes all contexts from a context hashtable.
     * It removes also the key from the hashtable.
     *
     * @param t hashtable
     * @return true if all contexts were removed (and was found in the hashtable)
     */
    private boolean removeAllContexts(Hashtable<Class<?>,?> t,
            Class<IContext> contextInterface, ArrayList<IContext> owner) {
        if ((t == null) || (contextInterface == null))
            return false;

        ArrayList<?> ar = (ArrayList<?>)t.get(contextInterface);
        if (ar == null)
            return false;

        boolean result = false;
        for (int i = ar.size()-1; i >= 0; i--) {
            IContext context = (IContext)ar.get(i);
            if (owner.contains(context)) {
                result |= ar.remove(context);
                owner.remove(context);
            }
        }
        if (ar.isEmpty())
            t.remove(contextInterface);
        return result;
    }

    /**
     * Method unregisters given context of given plug-in if the plug-in has
     * a permission to do it. The permission is approved if and only if
     * the context is implemented inside the requesting plug-in.
     *
     * @param pluginID ID of plug-in
     * @param context Context to unregister
     * @return true if the unregistration was successful, false otherwise
     */
    public boolean unregister(long pluginID, IContext context) {
        // check if the context is class
        if (context.getClass().isInterface())
            return false;

        // check for permission
        ArrayList<IContext> owner = contextOwners.get(pluginID);
        if (owner == null)
            return false;
        if (!owner.contains(context))
            return false;

        boolean result = false;

        result = removeContext(cpuContexts,context);
        result |= removeContext(memContexts,context);
        result |= removeContext(deviceContexts,context);
        result |= removeContext(compilerContexts,context);

        if (result == true)
            owner.remove(context);
        return result;
    }

    /**
     * This method unregisters all contexts that implements given interface,
     * if the plug-in has permission for it. The permission is approved if and
     * only if the contexts are implemented inside the plug-in.
     * 
     * @param pluginID ID of the plug-in
     * @param contextInterface Interface that should be unregistered
     * @return true if almost one context has been unregistered, false instead.
     */
    public boolean unregisterAll(long pluginID, Class<IContext> contextInterface) {
        // check if the context is class
        if (!contextInterface.getClass().isInterface())
            return false;

        // check for permission
        ArrayList<IContext> owner = contextOwners.get(pluginID);
        if (owner == null)
            return false;

        boolean result = false;

        result = removeAllContexts(cpuContexts, contextInterface, owner);
        result |= removeAllContexts(memContexts, contextInterface, owner);
        result |= removeAllContexts(compilerContexts, contextInterface, owner);
        result |= removeAllContexts(deviceContexts, contextInterface, owner);

        return result;
    }

    /**
     * This method should be called by the emuStudio. If the password is
     * not correct, it does nothing. It should be called after assignPassword()
     * method.
     *
     * @param password
     * @param computer
     */
    public void assignComputer(String password, IConnections computer) {
        if ((emuStudioHash == null) || (!emuStudioHash.equals(password)))
            return;

        this.computer = computer;
    }

    /**
     * Get registered CPU context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to CPU in
     * the abstract schema.
     *
     * If CPU has more than one context implementing required context interface,
     * the first one is returned. For specific context, use method
     * getCPUContext(pluginID,contextInterface,contextID).
     *
     * @param pluginID plug-in requesting the CPU context
     * @param contextInterface Interface of the context
     * @return ICPUContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public ICPUContext getCPUContext(long pluginID,
            Class<?> contextInterface) {
        // find the requested context
        ArrayList<ICPUContext> ar = cpuContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext cpuContext = ar.get(0); // the first one

        // check fot permission
        if (!checkPermission(pluginID, cpuContext))
            return null;
        
        return (ICPUContext)cpuContext;
    }

    /**
     * Get registered CPU context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to CPU in
     * the abstract schema.
     *
     * If CPU has more than one context implementing required context interface,
     * the one is returned that has the order of the index parameter. For
     * specific context, use method
     * getCPUContext(pluginID,contextInterface,contextID,index).
     *
     * @param pluginID plug-in requesting the CPU context
     * @param contextInterface Interface of the context
     * @param index the order of the context if they are more than one
     * @return ICPUContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public ICPUContext getCPUContext(long pluginID,
            Class<?> contextInterface, int index)
            throws ArrayIndexOutOfBoundsException {
        // find the requested context
        ArrayList<ICPUContext> ar = cpuContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext cpuContext = ar.get(index);

        // check fot permission
        if (!checkPermission(pluginID, cpuContext))
            return null;

        return (ICPUContext)cpuContext;
    }

    /**
     * Get registered CPU context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to CPU in the abstract schema.
     *
     * This method should be used when requested CPU has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the CPU context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @return ICPUContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public ICPUContext getCPUContext(long pluginID,
            Class<?> contextInterface, String contextID) {
        // find the requested context
        ArrayList<ICPUContext> ar = cpuContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find CPU context based on contextID
        IContext cpuContext = null;
        for (int i = 0; i < ar.size(); i++) {
            if (ar.get(i).getID().equals(contextID)) {
                cpuContext = ar.get(i);
                break;
            }
        }
        if (cpuContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, cpuContext))
            return null;

        return (ICPUContext)cpuContext;
    }

    /**
     * Get registered CPU context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to CPU in the abstract schema.
     *
     * This method should be used when requested CPU has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the CPU context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @param index the order of the context if they are more than one with
     * the same ID
     * @return ICPUContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public ICPUContext getCPUContext(long pluginID,
            Class<?> contextInterface, String contextID, int index)
            throws ArrayIndexOutOfBoundsException {
        // find the requested context
        ArrayList<ICPUContext> ar = cpuContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find CPU context based on contextID
        IContext cpuContext = null;
        for (int i = 0, j = 0; i < ar.size(); i++, j++) {
            if (ar.get(i).getID().equals(contextID)) {
                cpuContext = ar.get(i);
                if (j == index)
                    break;
            }
        }
        if (cpuContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, cpuContext))
            return null;

        return (ICPUContext)cpuContext;
    }

    /**
     * Get registered Compiler context.
     *
     * If the compiler has more than one context implementing required context
     * interface, the first one is returned. For specific context, use method
     * getCompilerContext(pluginID,contextInterface,contextID).
     *
     * @param pluginID plug-in requesting the Compiler context
     * @param contextInterface Interface of the context
     * @return ICompilerContext object if it is found, null otherwise
     */
    public ICompilerContext getCompilerContext(long pluginID,
            Class<?> contextInterface) {
        // find owner of context implementing requested context interface
        ArrayList<ICompilerContext> ar = compilerContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext compilerContext = ar.get(0); // the first one

        return (ICompilerContext)compilerContext;
    }

    /**
     * Get registered Compiler context.
     *
     * If the compiler has more than one context implementing required context
     * interface, the one is returned that has the order given by the index
     * parameter. For specific context, use method
     * getCompilerContext(pluginID,contextInterface,contextID,index).
     *
     * @param pluginID plug-in requesting the Compiler context
     * @param contextInterface Interface of the context
     * @param index the order of the context if they are more than one
     * @return ICompilerContext object if it is found, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public ICompilerContext getCompilerContext(long pluginID,
            Class<?> contextInterface, int index)
            throws ArrayIndexOutOfBoundsException {
        // find owner of context implementing requested context interface
        ArrayList<ICompilerContext> ar = compilerContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext compilerContext = ar.get(index);

        return (ICompilerContext)compilerContext;
    }

    /**
     * Get registered Compiler context, with specific ID.
     *
     * This method should be used when requested compiler has more than one
     * context implementing the same interface.
     * 
     * @param pluginID plug-in requesting the Compiler context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @return ICompilerContext object if it is found, null otherwise
     */
    public ICompilerContext getCompilerContext(long pluginID,
            Class<?> contextInterface, String contextID) {
        // find owner of context implementing requested context interface
        ArrayList<ICompilerContext> ar = compilerContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find compiler context based on contextID
        IContext compilerContext = null;
        for (int i = 0; i < ar.size(); i++) {
            if (ar.get(i).getID().equals(contextID)) {
                compilerContext = ar.get(i);
                break;
            }
        }
        return (ICompilerContext)compilerContext;
    }

    /**
     * Get registered Compiler context, with specific ID.
     *
     * This method should be used when requested compiler has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the Compiler context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @param index the order of the context if they are more than one with the
     * same ID
     * @return ICompilerContext object if it is found, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of the bounds
     */
    public ICompilerContext getCompilerContext(long pluginID,
            Class<?> contextInterface, String contextID, int index)
            throws ArrayIndexOutOfBoundsException {
        // find owner of context implementing requested context interface
        ArrayList<ICompilerContext> ar = compilerContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty()) {
            return null;
        }

        // find compiler context based on contextID
        IContext compilerContext = null;
        for (int i = 0,j = 0; i < ar.size(); i++, j++) {
            if (ar.get(i).getID().equals(contextID)) {
                compilerContext = ar.get(i);
                if (j == index)
                    break;
            }
        }
        return (ICompilerContext) compilerContext;
    }

    /**
     * Get registered memory context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to memory in
     * the abstract schema.
     *
     * If the memory has more than one context implementing required context
     * interface, the first one is returned. For specific context, use method
     * getMemoryContext(pluginID,contextInterface,contextID).
     *
     * @param pluginID plug-in requesting the memory context
     * @param contextInterface Interface of the context
     * @return IMemoryContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public IMemoryContext getMemoryContext(long pluginID,
            Class<?> contextInterface) {
        // find the requested context
        ArrayList<IMemoryContext> ar = memContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext memContext = ar.get(0); // the first one

        // check fot permission
        if (!checkPermission(pluginID, memContext))
            return null;

        return (IMemoryContext)memContext;
    }

    /**
     * Get registered memory context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to memory in
     * the abstract schema.
     *
     * If the memory has more than one context implementing required context
     * interface, the one is returned that has the order given by the index
     * parameter. For specific context, use method
     * getMemoryContext(pluginID,contextInterface,contextID,index).
     *
     * @param pluginID plug-in requesting the memory context
     * @param contextInterface Interface of the context
     * @param index the index of the context if they are more than one
     * @return IMemoryContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public IMemoryContext getMemoryContext(long pluginID,
            Class<?> contextInterface, int index)
            throws ArrayIndexOutOfBoundsException {
        // find the requested context
        ArrayList<IMemoryContext> ar = memContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext memContext = ar.get(index);

        // check fot permission
        if (!checkPermission(pluginID, memContext))
            return null;

        return (IMemoryContext)memContext;
    }

    /**
     * Get registered memory context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to memory in the abstract schema.
     *
     * This method should be used when requested memory has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the memory context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @return IMemoryContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public IMemoryContext getMemoryContext(long pluginID,
            Class<?> contextInterface, String contextID) {
        // find the requested context
        ArrayList<IMemoryContext> ar = memContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find memory context based on contextID
        IContext memContext = null;
        for (int i = 0; i < ar.size(); i++) {
            if (ar.get(i).getID().equals(contextID)) {
                memContext = ar.get(i);
                break;
            }
        }
        if (memContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, memContext))
            return null;

        return (IMemoryContext)memContext;
    }

    /**
     * Get registered memory context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to memory in the abstract schema.
     *
     * This method should be used when requested memory has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the memory context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @param index the order of the specific context, if they are more than one
     * with the same ID
     * @return IMemoryContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public IMemoryContext getMemoryContext(long pluginID,
            Class<?> contextInterface, String contextID, int index) {
        // find the requested context
        ArrayList<IMemoryContext> ar = memContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find memory context based on contextID
        IContext memContext = null;
        for (int i = 0, j=0; i < ar.size(); i++, j++) {
            if (ar.get(i).getID().equals(contextID)) {
                memContext = ar.get(i);
                if (index == j)
                    break;
            }
        }
        if (memContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, memContext))
            return null;

        return (IMemoryContext)memContext;
    }

    /**
     * Get registered device context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to device in
     * the abstract schema.
     *
     * If the device has more than one context implementing required context
     * interface, the first one is returned. For specific context, use method
     * getDeviceContext(pluginID,contextInterface,contextID).
     *
     * @param pluginID plug-in requesting the device context
     * @param contextInterface Interface of the context
     * @return IDeviceContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public IDeviceContext getDeviceContext(long pluginID,
            Class<?> contextInterface) {
        // find the requested context
        ArrayList<IDeviceContext> ar = deviceContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext deviceContext = ar.get(0); // the first one

        // check fot permission
        if (!checkPermission(pluginID, deviceContext))
            return null;

        return (IDeviceContext)deviceContext;
    }

    /**
     * Get registered device context, if plug-in has the permission to access it.
     * The permission is approved, if the plug-in is connected to device in
     * the abstract schema.
     *
     * If the device has more than one context implementing required context
     * interface, the user can select the correct with the index parameter.
     * For specific context, use method
     * getDeviceContext(pluginID,contextInterface,contextID,index).
     *
     * @param pluginID plug-in requesting the device context
     * @param contextInterface Interface of the context
     * @param index index of the context implementation
     * @return IDeviceContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public IDeviceContext getDeviceContext(long pluginID,
            Class<?> contextInterface, int index) throws ArrayIndexOutOfBoundsException {
        // find the requested context
        ArrayList<IDeviceContext> ar = deviceContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        IContext deviceContext = ar.get(index);

        // check fot permission
        if (!checkPermission(pluginID, deviceContext))
            return null;

        return (IDeviceContext)deviceContext;
    }

    /**
     * Get registered device context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to device in the abstract schema.
     *
     * This method should be used when requested device has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the device context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @return IDeviceContext object if it is found and the plug-in has the
     *         permission, null otherwise
     */
    public IDeviceContext getDeviceContext(long pluginID,
            Class<?> contextInterface, String contextID) {
        // find the requested context
        ArrayList<IDeviceContext> ar = deviceContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find device context based on contextID
        IContext deviceContext = null;
        for (int i = 0; i < ar.size(); i++) {
            if (ar.get(i).getID().equals(contextID)) {
                deviceContext = ar.get(i);
                break;
            }
        }
        if (deviceContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, deviceContext))
            return null;

        return (IDeviceContext)deviceContext;
    }

    /**
     * Get registered device context with the specific ID, if plug-in has the
     * permission to access it. The permission is approved, if the plug-in
     * is connected to device in the abstract schema.
     *
     * This method should be used when requested device has more than one
     * context implementing the same interface.
     *
     * @param pluginID plug-in requesting the device context
     * @param contextInterface Interface of the context
     * @param contextID specific case-sensitive ID of context
     * @param index the order of the specific context if they are more than one
     * with the same ID
     * @return IDeviceContext object if it is found and the plug-in has the
     *         permission, null otherwise
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds
     */
    public IDeviceContext getDeviceContext(long pluginID,
            Class<?> contextInterface, String contextID, int index)
            throws ArrayIndexOutOfBoundsException {
        // find the requested context
        ArrayList<IDeviceContext> ar = deviceContexts.get(contextInterface);
        if ((ar == null) || ar.isEmpty())
            return null;

        // find device context based on contextID
        IContext deviceContext = null;
        for (int i = 0, j = 0; i < ar.size(); i++, j++) {
            if (ar.get(i).getID().equals(contextID)) {
                deviceContext = ar.get(i);
                if (index == j)
                    break;
            }
        }
        if (deviceContext == null)
            return null;

        // check fot permission
        if (!checkPermission(pluginID, deviceContext))
            return null;

        return (IDeviceContext)deviceContext;
    }

    /**
     * This method checks if the hash of the contextInterface equals given
     * hash string. The contextInterface hash is computed.
     *
     * @param contextInterface context interface for checking its hash
     * @param hash comparison hash
     * @return true if the hashes match in case-isensitive manner,
     *         false otherwise
     */
    private static boolean checkHash(Class<?> contextInterface, String hash) {
        String computedHash = computeHash(contextInterface);
        if (computedHash == null)
            return false;
        return (computedHash.equalsIgnoreCase(hash)) ? true : false;
    }

    /**
     * This method check the plug-in for the permission to access specified
     * context.
     *
     * The permission is granted if and only if context owner is connected
     * with the requesting plug-in in the abstract schema.
     *
     * @param pluginID ID of requesting plug-in
     * @param context Context wanted
     * @return true if the plug-in is approved to access the context, false
     *         otherwise
     */
    private boolean checkPermission(long pluginID, IContext context) {
        // check if it is possible to check the plug-in for the permission
        if (computer == null)
            return false;

        // first it must be found the owner of the Context.
        Long owner = null;
        Enumeration<Long> t = contextOwners.keys();

        while (t.hasMoreElements()) {
            long pID = t.nextElement();
            ArrayList<IContext> con = contextOwners.get(pID);
            if (con == null)
                continue;
            if (con.contains(context)) {
                owner = pID;
                break;
            }
        }
        // contex was not found in owners? This would be emuLib BUG!!
        if (owner == null)
            return false;

        // THIS is the permission check
        return computer.isConnected(pluginID, owner);
    }

    /**
     * Assigns a hash to the emuStudioHash variable. This hash represents
     * "password" by which the emuStudio is allowed to perform critical operations
     * in the emuLib. The operations must be strictly proteted from plug-ins.
     * They include e.g. providing information about plug-in connections.
     *
     * This method is called only once, by the emuStudio. After each next call,
     * it does nothing and returns false.
     *
     * @param password emuStudio hash string, the "password".
     * @return true if the assignment was successfull (first call), false
     *         otherwise.
     */
    public static boolean assignPassword(String password) {
        if (emuStudioHash == null) {
            emuStudioHash = password;
            return true;
        }
        return false;
    }

    /**
     * Compute hash of a plug-in context interface. Uses SHA-1 method.
     *
     * @param inter  Interface to computer hash of
     * @return SHA-1 hash string
     */
    private static String computeHash(Class<?> inter) {
        int i;
        Method[] methods, met;
        String hash = "";

        met = inter.getDeclaredMethods(); //  .getMethods();
        ArrayList me = new ArrayList();
        for (i = 0; i < met.length; i++)
            me.add(met[i]);
        Collections.sort(me, new Comparator() {

            @Override
            public int compare(Object o1, Object o2) {
                Method m1 = (Method)o1;
                Method m2 = (Method)o2;

                return m1.getName().compareTo(m2.getName());
            }

        });
        methods = (Method[])me.toArray(new Method[0]);
        me.clear();
        me = null;

        for (i = 0; i < methods.length; i++) {
            hash += methods[i].getGenericReturnType().toString() + " ";
            hash += methods[i].getName() + "(";
            Class<?>[] params = methods[i].getParameterTypes();
            for (int j = 0; j < params.length; j++)
                hash += params[j].getName() + ",";
            hash += ");";
        }
        try {
            return SHA1(hash);
        } catch(Exception e) {
            return null;
        }
    }

    /**
     * Convert data to HEXadecimal string. Letters are in upper case.
     *
     * @param data data to convert
     * @return hexadecimal string.
     */
    private static String convertToHex(byte[] data) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            int halfbyte = (data[i] >>> 4) & 0x0F;
            int two_halfs = 0;
            do {
                if ((0 <= halfbyte) && (halfbyte <= 9))
                    buf.append((char) ('0' + halfbyte));
                else
                    buf.append((char) ('A' + (halfbyte - 10)));
                halfbyte = data[i] & 0x0F;
            } while(two_halfs++ < 1);
        }
        return buf.toString();
    }

    /**
     * Compute MD5 hash string. Letters in the hash string are in upper-case.
     *
     * @param text Data to make hash from
     * @return MD5 hash Hexadecimal string, null if some exception has been
     * catched
     */
    public static String MD5(String text) {
        try {
            MessageDigest md;
            md = MessageDigest.getInstance("MD5");
            byte[] md5hash = new byte[32];
            md.update(text.getBytes("iso-8859-1"), 0, text.length());
            md5hash = md.digest();
            return convertToHex(md5hash);
        }  catch (NoSuchAlgorithmException e) {}
        catch (UnsupportedEncodingException r) {}
        return null;
    }

    /**
     * Compute SHA-1 hash string. Letters in the hash string are in upper-case.
     *
     * @param text Data to make hash from
     * @return SHA-1 hash Hexadecimal string, null if some exception has been
     * catched
     */
    public static String SHA1(String text) {
        try {
            MessageDigest md;
            md = MessageDigest.getInstance("SHA-1");
            byte[] sha1hash = new byte[40];
            md.update(text.getBytes("iso-8859-1"), 0, text.length());
            sha1hash = md.digest();
            return convertToHex(sha1hash);
        } catch (NoSuchAlgorithmException e) {}
        catch (UnsupportedEncodingException r) {}
        return null;
    }

}
