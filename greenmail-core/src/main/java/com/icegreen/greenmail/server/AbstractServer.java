/*
 * Copyright (c) 2014 Wael Chatila / Icegreen Technologies. All Rights Reserved.
 * This software is released under the Apache license 2.0
 */
package com.icegreen.greenmail.server;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Vector;

/**
 * @author Wael Chatila
 * @version $Id: $
 * @since Feb 2, 2006
 */
public abstract class AbstractServer extends Service {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final InetAddress bindTo;
    protected ServerSocket serverSocket = null;
    protected Managers managers;
    protected ServerSetup setup;
    private Vector<ProtocolHandler> handlers = new Vector<ProtocolHandler>();

    protected AbstractServer(ServerSetup setup, Managers managers) {
        this.setup = setup;
        try {
            bindTo = (setup.getBindAddress() == null)
                    ? InetAddress.getByName("0.0.0.0")
                    : InetAddress.getByName(setup.getBindAddress());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        this.managers = managers;
    }

    /**
     * Create a new, specific protocol handler such as for IMAP.
     *
     * @param clientSocket the client socket to use.
     * @return the new protocol handler.
     */
    protected abstract ProtocolHandler createProtocolHandler(Socket clientSocket);

    protected synchronized ServerSocket openServerSocket() throws IOException {
        ServerSocket ret = null;
        IOException retEx = null;
        for (int i = 0; i < 25 && (null == ret); i++) {
            try {
                if (setup.isSecure()) {
                    ret = DummySSLServerSocketFactory.getDefault().createServerSocket(setup.getPort(), 0, bindTo);
                } else {
                    ret = new ServerSocket(setup.getPort(), 0, bindTo);
                }
            } catch (BindException e) {
                try {
                    retEx = e;
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    if(log.isDebugEnabled()) {
                        log.debug("Can not open port, retrying ...", e);
                    }
                }
            }
        }
        if (null == ret && null != retEx) {
            throw retEx;
        }
        return ret;
    }

    public void run() {
        try {
            try {
                serverSocket = openServerSocket();
                setRunning(true);
                synchronized (this) {
                    this.notifyAll();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            while (keepOn()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    if (!keepOn()) {
                        clientSocket.close();
                    } else {
                        final ProtocolHandler handler = createProtocolHandler(clientSocket);
                        addHandler(handler);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handler.run();
                                 // Make sure to deregister, see https://github.com/greenmail-mail-test/greenmail/issues/18
                                removeHandler(handler);
                            }
                        }).start();
                    }
                } catch (IOException ignored) {
                    //ignored
                }
            }
        } finally {
            quit();
        }
    }

    /**
     * Adds a protocol handler, for eg. shutting down.
     *
     * @param handler the handler.
     */
    private void addHandler(ProtocolHandler handler) {
        handlers.add(handler);
    }

    /**
     * Adds a protocol handler, for eg. shutting down.
     *
     * @param handler the handler.
     */
    private void removeHandler(ProtocolHandler handler) {
        handlers.remove(handler);
    }

    public synchronized void quit() {
        try {
            synchronized (handlers) {
                for (ProtocolHandler handler : handlers) {
                    handler.close();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            if (null != serverSocket ) {
                if(!serverSocket.isClosed()) {
                    serverSocket.close();
                }
                serverSocket = null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getBindTo() {
        return bindTo.getHostAddress();
    }

    public int getPort() {
        return setup.getPort();
    }

    public String getProtocol() {
        return setup.getProtocol();
    }

    public ServerSetup getServerSetup() {
        return setup;
    }

    public String toString() {
        return null != setup ? setup.getProtocol() + ':' + setup.getPort() : super.toString();
    }
}