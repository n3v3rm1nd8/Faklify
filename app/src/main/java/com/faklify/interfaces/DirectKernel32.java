package com.faklify.interfaces;

// Interfaz para que JNA encuentre el método de Windows
public interface DirectKernel32 extends com.sun.jna.Library {
    // Cargamos kernel32 con opciones para que use Unicode por defecto

    DirectKernel32 INSTANCE = com.sun.jna.Native.load("kernel32", DirectKernel32.class,
            com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);

    // Forzamos el nombre de la función a SetDllDirectoryW
    boolean SetDllDirectoryW(String lpPathName);
}
