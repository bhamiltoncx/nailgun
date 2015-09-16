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

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.ByteBuffer;

import java.net.Socket;

/**
 * Implements a {@link Socket} backed by a native Win32 named pipe.
 *
 * Instances of this class always return {@code null} for
 * {@link Socket#getInetAddress()}, {@link Socket#getLocalAddress()},
 * {@link Socket#getLocalSocketAddress()}, {@link Socket#getRemoteSocketAddress()}.
 */
public class NGWin32NamedPipeSocket extends Socket {
  private final HANDLE handle;
  private final CloseCallback closeCallback;
  private final InputStream is;
  private final OutputStream os;

  interface CloseCallback {
    void onNamedPipeSocketClose(HANDLE handle) throws IOException;
  }

  /**
   * Creates a Unix domain socket backed by a native file descriptor.
   */
  public NGWin32NamedPipeSocket(
      HANDLE handle,
      CloseCallback closeCallback) {
    this.handle = handle;
    this.closeCallback = closeCallback;
    this.is = new NGWin32NamedPipeSocketInputStream(handle);
    this.os = new NGWin32NamedPipeSocketOutputStream(handle);
  }

  public InputStream getInputStream() {
    return is;
  }

  public OutputStream getOutputStream() {
    return os;
  }

  public void close() throws IOException {
    closeCallback.onNamedPipeSocketClose(handle);
  }

  private static class NGWin32NamedPipeSocketInputStream extends InputStream {
    private final HANDLE handle;

    public NGWin32NamedPipeSocketInputStream(HANDLE handle) {
      this.handle = handle;
    }

    public int read() throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(1);
      int result;
      if (doRead(buf) == 0) {
        result = -1;
      } else {
        // Make sure to & with 0xFF to avoid sign extension
        result = 0xFF & buf.get();
      }
      return result;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      ByteBuffer buf = ByteBuffer.wrap(b, off, len);
      int result = doRead(buf);
      if (result == 0) {
        result = -1;
      }
      return result;
    }

    private int doRead(ByteBuffer buf) throws IOException {
      IntByReference lpNumberOfBytesRead = new IntByReference();
      if (!NGWin32NamedPipeLibrary.INSTANCE.ReadFile(
              handle,
              buf,
              buf.remaining(),
              lpNumberOfBytesRead,
              /* lpOverlapped */ null)) {
        throw new IOException(String.format("Could not read: %d", Native.getLastError()));
      }
      return lpNumberOfBytesRead.getValue();
    }
  }

  private static class NGWin32NamedPipeSocketOutputStream extends OutputStream {
    private final HANDLE handle;

    public NGWin32NamedPipeSocketOutputStream(HANDLE handle) {
      this.handle = handle;
    }

    public void write(int b) throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(1);
      buf.put(0, (byte) (0xFF & b));
      doWrite(buf);
    }

    public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return;
      }
      ByteBuffer buf = ByteBuffer.wrap(b, off, len);
      doWrite(buf);
    }

    private void doWrite(ByteBuffer buf) throws IOException {
      IntByReference lpNumberOfBytesWritten = new IntByReference();
      if (!NGWin32NamedPipeLibrary.INSTANCE.WriteFile(
              handle,
              buf,
              buf.remaining(),
              lpNumberOfBytesWritten,
              /* lpOverlapped */ null)) {
        throw new IOException(String.format("Could not write: %d", Native.getLastError()));
      }
      if (lpNumberOfBytesWritten.getValue() != buf.remaining()) {
        throw new IOException("Could not write " + buf.remaining() + " bytes as requested " +
                              "(wrote " + lpNumberOfBytesWritten.getValue() + " bytes instead)");
      }
    }
  }
}
