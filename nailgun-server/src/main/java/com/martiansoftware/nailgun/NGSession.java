/*

 Copyright 2004-2012, Martian Software, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */
package com.martiansoftware.nailgun;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the NailGun stream from the client through the command, then hands off
 * processing to the appropriate class. The NGSession obtains its sockets from
 * an NGSessionPool, which created this NGSession.
 *
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
public class NGSession extends Thread {

    private static final Logger LOGGER = Logger.getLogger(NGSession.class.toString());

    /**
     * The server this NGSession is working for
     */
    private NGServer server = null;
    /**
     * The pool this NGSession came from, and to which it will return itself
     */
    private NGSessionPool sessionPool = null;
    /**
     * Synchronization object
     */
    private Object lock = new Object();
    /**
     * The next socket this NGSession has been tasked with processing (by
     * NGServer)
     */
    private Socket nextSocket = null;
    /**
     * True if the server has been shutdown and this NGSession should terminate
     * completely
     */
    private boolean done = false;
    /**
     * The instance number of this NGSession. That is, if this is the Nth
     * NGSession to be created, then this is the value for N.
     */
    private long instanceNumber = 0;

    /**
     * The interval to wait between heartbeats before considering the client to have disconnected.
     */
    private int heartbeatTimeoutMillis = NGConstants.HEARTBEAT_TIMEOUT_MILLIS;

    /**
     * A lock shared among all NGSessions
     */
    private static Object sharedLock = new Object();
    /**
     * The instance counter shared among all NGSessions
     */
    private static long instanceCounter = 0;
    /**
     * signature of main(String[]) for reflection operations
     */
    private static Class[] mainSignature;
    /**
     * signature of nailMain(NGContext) for reflection operations
     */
    private static Class[] nailMainSignature;

    /**
     * A ClassLoader that may be set by a client. Defaults to the classloader of this class.
     */
    public static volatile ClassLoader classLoader = null; // initialized in the static initializer - see below

    static {
        // initialize the signatures
        mainSignature = new Class[1];
        mainSignature[0] = String[].class;

        nailMainSignature = new Class[1];
        nailMainSignature[0] = NGContext.class;

        try {
            classLoader = NGSession.class.getClassLoader();
        } catch (SecurityException e) {
            throw e;
        }

    }

    /**
     * Creates a new NGSession running for the specified NGSessionPool and
     * NGServer.
     *
     * @param sessionPool The NGSessionPool we're working for
     * @param server The NGServer we're working for
     */
    NGSession(NGSessionPool sessionPool, NGServer server) {
        super();
        this.sessionPool = sessionPool;
        this.server = server;
        this.heartbeatTimeoutMillis = server.getHeartbeatTimeout();

        synchronized (sharedLock) {
            this.instanceNumber = ++instanceCounter;
        }
//		server.out.println("Created NGSession " + instanceNumber);
    }

    /**
     * Shuts down this NGSession gracefully
     */
    void shutdown() {
        done = true;
        synchronized (lock) {
            nextSocket = null;
            lock.notifyAll();
        }
    }

    /**
     * Instructs this NGSession to process the specified socket, after which
     * this NGSession will return itself to the pool from which it came.
     *
     * @param socket the socket (connected to a client) to process
     */
    public void run(Socket socket) {
        synchronized (lock) {
            nextSocket = socket;
            lock.notify();
        }
        Thread.yield();
    }

    /**
     * Returns the next socket to process. This will block the NGSession thread
     * until there's a socket to process or the NGSession has been shut down.
     *
     * @return the next socket to process, or
     * <code>null</code> if the NGSession has been shut down.
     */
    private Socket nextSocket() {
        Socket result = null;
        synchronized (lock) {
            result = nextSocket;
            while (!done && result == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    done = true;
                }
                result = nextSocket;
            }
            nextSocket = null;
        }
        return (result);
    }

    /**
     * The main NGSession loop. This gets the next socket to process, runs the
     * nail for the socket, and loops until shut down.
     */
    public void run() {

        updateThreadName(null);

        LOGGER.fine("Waiting for first client connection.");
        Socket socket = nextSocket();
        while (socket != null) {
            try {
                LOGGER.fine("Received connection from " + socket.getInetAddress().getHostAddress());
                DataInputStream sockin = new DataInputStream(socket.getInputStream());
                DataOutputStream sockout = new DataOutputStream(socket.getOutputStream());

                // client info - command line arguments and environment
                List remoteArgs = new java.util.ArrayList();
                Properties remoteEnv = new Properties();

                String cwd = null;			// working directory
                String command = null;		// alias or class name

                // read everything from the client up to and including the command
                while (command == null) {
                    int bytesToRead = sockin.readInt();
                    byte chunkType = sockin.readByte();

                    byte[] b = new byte[(int) bytesToRead];
                    sockin.readFully(b);
                    String line = new String(b, "US-ASCII");

                    switch (chunkType) {

                        case NGConstants.CHUNKTYPE_ARGUMENT:
                            //	command line argument
                            remoteArgs.add(line);
                            LOGGER.fine("Received command line argument: " + line);
                            break;

                        case NGConstants.CHUNKTYPE_ENVIRONMENT:
                            //	parse environment into property
                            int equalsIndex = line.indexOf('=');
                            if (equalsIndex > 0) {
                                String key = line.substring(0, equalsIndex);
                                String value = line.substring(equalsIndex + 1);
                                remoteEnv.setProperty(key, value);
                                LOGGER.finest("Setting env: key('" + key + "')=value('" + value + "').");
                            }
                            break;

                        case NGConstants.CHUNKTYPE_COMMAND:
                            // 	command (alias or classname)
                            command = line;
                            LOGGER.fine("Received command: " + command);
                            break;

                        case NGConstants.CHUNKTYPE_WORKINGDIRECTORY:
                            //	client working directory
                            cwd = line;
                            LOGGER.fine("Working directory: " + cwd);
                            break;

                        default:	// freakout?
                            LOGGER.warning("Unknown chunk type: " + chunkType);
                    }
                }

                updateThreadName(socket.getInetAddress().getHostAddress() + ": " + command);

                // can't create NGInputStream until we've received a command, because at
                // that point the stream from the client will only include stdin and stdin-eof
                // chunks
                InputStream in = new NGInputStream(sockin, sockout, server.out, heartbeatTimeoutMillis);
                PrintStream out = new PrintStream(new NGOutputStream(sockout, NGConstants.CHUNKTYPE_STDOUT));
                PrintStream err = new PrintStream(new NGOutputStream(sockout, NGConstants.CHUNKTYPE_STDERR));
                PrintStream exit = new PrintStream(new NGOutputStream(sockout, NGConstants.CHUNKTYPE_EXIT));

                LOGGER.fine("Redirecting stdin, stdout, and stderr.");

                // ThreadLocal streams for System.in/out/err redirection
                ((ThreadLocalInputStream) System.in).init(in);
                ((ThreadLocalPrintStream) System.out).init(out);
                ((ThreadLocalPrintStream) System.err).init(err);

                try {
                    Alias alias = server.getAliasManager().getAlias(command);
                    Class cmdclass = null;
                    if (alias != null) {
                        cmdclass = alias.getAliasedClass();
                    } else if (server.allowsNailsByClassName()) {
                        cmdclass = Class.forName(command, true, classLoader);
                    } else {
                        cmdclass = server.getDefaultNailClass();
                    }

                    Object[] methodArgs = new Object[1];
                    Method mainMethod = null; // will be either main(String[]) or nailMain(NGContext)
                    String[] cmdlineArgs = (String[]) remoteArgs.toArray(new String[remoteArgs.size()]);

                    boolean isStaticNail = true; // See: NonStaticNail.java

                    Class[] interfaces = cmdclass.getInterfaces();

                    for (int i = 0; i < interfaces.length; i++){
                        if (interfaces[i].equals(NonStaticNail.class)){
                            isStaticNail = false; break;
                        }
                    }

                    if (!isStaticNail){

                        mainMethod = cmdclass.getMethod("nailMain", new Class[]{ String[].class });
                        methodArgs[0] = cmdlineArgs;

                    } else {

                        try {
                            mainMethod = cmdclass.getMethod("nailMain", nailMainSignature);
                            NGContext context = new NGContext();
                            context.setArgs(cmdlineArgs);
                            context.in = in;
                            context.out = out;
                            context.err = err;
                            context.setCommand(command);
                            context.setExitStream(exit);
                            context.setNGServer(server);
                            context.setEnv(remoteEnv);
                            context.setInetAddress(socket.getInetAddress());
                            context.setPort(socket.getPort());
                            context.setWorkingDirectory(cwd);
                            methodArgs[0] = context;
                        } catch (NoSuchMethodException toDiscard) {
                            // that's ok - we'll just try main(String[]) next.
                        }

                        if (mainMethod == null) {
                            mainMethod = cmdclass.getMethod("main", mainSignature);
                            methodArgs[0] = cmdlineArgs;
                        }

                    }

                    if (mainMethod != null) {
                        LOGGER.fine("Invoking main method: " + mainMethod);

                        server.nailStarted(cmdclass);
                        NGSecurityManager.setExit(exit);

                        try {
                            if (isStaticNail){
                                mainMethod.invoke(null, methodArgs);
                            } else {
                                mainMethod.invoke(cmdclass.newInstance(), methodArgs);
                            }
                        } catch (InvocationTargetException ite) {
                            LOGGER.log(Level.SEVERE, "Got InvocationTargetException, rethrowing", e);
                            throw (ite.getCause());
                        } catch (InstantiationException e){
                            LOGGER.log(Level.SEVERE, "Got InstantiationException, rethrowing", e);
                            throw (e);
                        } catch (IllegalAccessException e){
                            LOGGER.log(Level.SEVERE, "Got IllegalAccessException, rethrowing", e);
                            throw (e);
                        } catch (Throwable t) {
                            LOGGER.log(Level.SEVERE, "Got Throwable, rethrowing", e);
                            throw (t);
                        } finally {
                            LOGGER.fine("Nail finished.");
                            server.nailFinished(cmdclass);
                        }
                        LOGGER.fine("Writing exit command with successful status 0 to client.");
                        exit.println(0);
                    }

                } catch (NGExitException exitEx) {
                    Level logLevel = (exitEx.getStatus() == 0) ? Level.FINE : Level.WARN;
                    LOGGER.log(logLevel, "Caught NGExitException, cleaning up session and writing exit command to client.", exitEx);
                    in.close();
                    exit.println(exitEx.getStatus());
                    server.out.println(Thread.currentThread().getName() + " exited with status " + exitEx.getStatus());
                } catch (Throwable t) {
                    LOGGER.log(Level.WARN, "Caught Throwable, cleaning up session and writing exit exception with status " + NGContext.EXIT_EXCEPTION + " to client.", t);
                    in.close();
                    t.printStackTrace();
                    exit.println(NGConstants.EXIT_EXCEPTION); // remote exception constant
                }

                LOGGER.fine("Flushing socket output stream.");
                sockout.flush();
                LOGGER.fine("Closing client socket.");
                socket.close();

            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "Something bad happened: ", t);
                t.printStackTrace();
            }

            LOGGER.fine("Restoring stdin, stdout, and stderr.");
            ((ThreadLocalInputStream) System.in).init(null);
            ((ThreadLocalPrintStream) System.out).init(null);
            ((ThreadLocalPrintStream) System.err).init(null);

            updateThreadName(null);
            sessionPool.give(this);
            LOGGER.fine("Waiting for next client connection.");
            socket = nextSocket();
        }

//		server.out.println("Shutdown NGSession " + instanceNumber);
    }

    /**
     * Updates the current thread name (useful for debugging).
     */
    private void updateThreadName(String detail) {
        setName("NGSession " + instanceNumber + ": " + ((detail == null) ? "(idle)" : detail));
    }
}
