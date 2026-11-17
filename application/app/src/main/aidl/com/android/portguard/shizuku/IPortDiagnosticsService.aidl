package com.android.portguard.shizuku;

interface IPortDiagnosticsService {
    void destroy() = 16777114;
    String runReadOnlyDiagnostics() = 1;
}
