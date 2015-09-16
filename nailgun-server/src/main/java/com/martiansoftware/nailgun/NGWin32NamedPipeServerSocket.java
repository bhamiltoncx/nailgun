/*

 Copyright 2004-2015, Martian Software, Inc.

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

import com.martiansoftware.nailgun.NGWin32NamedPipeLibrary.HANDLE;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.LinkedBlockingQueue;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * Implements a {@link ServerSocket} which binds to a local Windows named pipe
 * and returns instances of {@link NGWin32NamedPipeSocket} from
 * {@link #accept()}.
 */
public class NGWin32NamedPipeServerSocket extends ServerSocket {
  private static final String WIN32_PIPE_PREFIX = "\\\\.\\pipe\\";
  private static final int BUFFER_SIZE = 65535;
  private final LinkedBlockingQueue<HANDLE> openHandles;
  private final NGWin32NamedPipeSocket.CloseCallback closeCallback;
  private final String path;
  private final int maxInstances;

  /**
   * Constructs and binds a Win32 named pipe server socket to the specified path.
   */
  public NGWin32NamedPipeServerSocket(String path) throws IOException {
    this(NGWin32NamedPipeLibrary.PIPE_UNLIMITED_INSTANCES, path);
  }

  /**
   * Constructs and binds a Win32 named pipe server socket to the specified path
   * with the specified maximum number of instances.
   */
  public NGWin32NamedPipeServerSocket(int maxInstances, String path) throws IOException {
    this.openHandles = new LinkedBlockingQueue<HANDLE>();
    this.closeCallback = new NGWin32NamedPipeSocket.CloseCallback() {
      public void onNamedPipeSocketClose(HANDLE handle) throws IOException {
        if (openHandles.remove(handle)) {
          closeNamedPipe(handle);
        }
      }      
    };
    this.maxInstances = maxInstances;
    if (!path.startsWith(WIN32_PIPE_PREFIX)) {
      this.path = WIN32_PIPE_PREFIX + path;
    } else {
      this.path = path;
    }
  }

  public void bind(SocketAddress endpoint) throws IOException {
    throw new IOException("Win32 named pipes do not support bind(), pass path to constructor");
  }

  public Socket accept() throws IOException {
    HANDLE handle = NGWin32NamedPipeLibrary.INSTANCE.CreateNamedPipe(                                
      Native.toCharArray(path),
      NGWin32NamedPipeLibrary.PIPE_ACCESS_DUPLEX | NGWin32NamedPipeLibrary.PIPE_TYPE_BYTE | NGWin32NamedPipeLibrary.PIPE_READMODE_BYTE,
      NGWin32NamedPipeLibrary.PIPE_WAIT,
      maxInstances,
      BUFFER_SIZE,
      BUFFER_SIZE,
      /* nDefaultTimeOut */ 0,
      /* lpSecurityAttributes */ null);
    if (handle == NGWin32NamedPipeLibrary.INVALID_HANDLE_VALUE) {
      throw new IOException(String.format("Could not create named pipe, error %d", Native.getLastError()));
    }
    openHandles.add(handle);

    // This will block until the next client connects.
    if (!NGWin32NamedPipeLibrary.INSTANCE.ConnectNamedPipe(handle, null)) {
      String message = String.format("Could not connect named pipe, error %d", Native.getLastError());
      if (openHandles.remove(handle)) {
        closeNamedPipe(handle);
      }
      throw new IOException(message);
    }
    return new NGWin32NamedPipeSocket(handle, closeCallback);
  }

  public void close() throws IOException {
    List<HANDLE> handlesToClose = new ArrayList<HANDLE>();
    openHandles.drainTo(handlesToClose);
    for (HANDLE handle : handlesToClose) {
      closeNamedPipe(handle);
    }
  }

  private static void closeNamedPipe(HANDLE handle) throws IOException {
    try {
      if (!NGWin32NamedPipeLibrary.INSTANCE.DisconnectNamedPipe(handle)) {
        throw new IOException(String.format("Could not disconnect named pipe, error %d", Native.getLastError()));
      }
    } finally {
      if (!NGWin32NamedPipeLibrary.INSTANCE.CloseHandle(handle)) {
        throw new IOException(String.format("Could not close named pipe, error %d", Native.getLastError()));
      }
    }
  }
}
