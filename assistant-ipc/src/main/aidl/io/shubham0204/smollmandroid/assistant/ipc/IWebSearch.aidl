package io.shubham0204.smollmandroid.assistant.ipc;

// Served by the web-search package, the only package that holds INTERNET.
// The caller supplies a query string; the endpoint is fixed in the
// web-search package's own settings.
interface IWebSearch {
    // Plain text of at most a few KiB: one "title\nurl\nsnippet" block per result.
    String search(String query);

    // The configured endpoint, for display in the confirmation dialog.
    String endpoint();
}
