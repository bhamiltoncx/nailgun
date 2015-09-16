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

import java.io.IOException;
import java.nio.ByteBuffer;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.FromNativeContext;
import com.sun.jna.IntegerType;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.ptr.ByReference;

import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Utility class to bridge native Win32 named pipe calls to Java using JNA.
 */
public interface NGWin32NamedPipeLibrary extends StdCallLibrary {
  int PIPE_ACCESS_DUPLEX = 3;
  int PIPE_TYPE_BYTE = 0;
  int PIPE_READMODE_BYTE = 0;
  int PIPE_WAIT = 0;
  int PIPE_UNLIMITED_INSTANCES = 255;
    
  HANDLE INVALID_HANDLE_VALUE =
    new HANDLE(Pointer.createConstant(Pointer.SIZE == 8 ? -1 : 0xFFFFFFFFL));

  NGWin32NamedPipeLibrary INSTANCE = (NGWin32NamedPipeLibrary) Native.loadLibrary(
      "kernel32", NGWin32NamedPipeLibrary.class, W32APIOptions.UNICODE_OPTIONS);

  public static class DWORD extends IntegerType {
    public static final int SIZE = 4;
    public DWORD() {
      this(0);
    }

    public DWORD(long value) {
      super(SIZE, value, true);
    }
  }

  public class DWORDByReference extends ByReference {
    public DWORDByReference() {
      this(new DWORD(0));
    }

    public DWORDByReference(DWORD value) {
      super(DWORD.SIZE);
      setValue(value);
    }

    public void setValue(DWORD value) {
      getPointer().setInt(0, value.intValue());
    }

    public DWORD getValue() {
      return new DWORD(getPointer().getInt(0));
    }
  }

  public static class SECURITY_ATTRIBUTES extends Structure {
    public DWORD dwLength;
    public Pointer lpSecurityDescriptor;
    public boolean bInheritHandle;
    protected List getFieldOrder() {
      return Arrays.asList(new String[] { "dwLength", "lpSecurityDescriptor", "bInheritHandle" });
    }
    public SECURITY_ATTRIBUTES() {
      dwLength = new DWORD(size());
    }
  }

  public static class HANDLE extends PointerType {
    public HANDLE() {
    }
    
    public HANDLE(Pointer p) {
      setPointer(p);
    }

    public Object fromNative(Object nativeValue, FromNativeContext context) {
      Object o = super.fromNative(nativeValue, context);
      if (INVALID_HANDLE_VALUE.equals(o)) {
	return INVALID_HANDLE_VALUE;
      }
      return o;
    }
  }
  
  HANDLE CreateNamedPipe(
    char[] lpName,
    DWORD dwOpenMode,
    DWORD dwPipeMode,
    DWORD nMaxInstances,
    DWORD nOutBufferSize,
    DWORD nInBufferSize,
    DWORD nDefaultTimeOut,
    SECURITY_ATTRIBUTES lpSecurityAttributes);
  boolean ConnectNamedPipe(HANDLE hNamedPipe, Pointer lpOverlapped);
  boolean ReadFile(
    HANDLE hFile,
    ByteBuffer lpBuffer,
    DWORD nNumberOfBytesToRead,
    DWORDByReference lpNumberOfBytesRead,
    Pointer lpOverlapped);
}
